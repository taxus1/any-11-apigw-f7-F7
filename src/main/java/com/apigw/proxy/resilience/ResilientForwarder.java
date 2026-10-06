package com.apigw.proxy.resilience;

import com.apigw.domain.route.CircuitBreakerPolicy;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.RetryPolicy;
import com.apigw.proxy.config.ResilienceProperties;
import com.apigw.proxy.forward.BufferedUpstreamResponse;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.forward.UpstreamResponse;
import com.apigw.proxy.userauth.OutboundAuth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

/**
 * 熔断 × 重试的总编排（只在某路由开了其中至少一个时介入；都没开走 {@code UpstreamForwarder.forward}
 * 老链路，本组件不出现）。它把「对上游打几发、每发成败怎么记账、什么时候彻底放弃」收拢到一处，
 * 过滤器只负责在拿到终局结果后写回调用方。
 *
 * <h3>两者配合的口径（关键）</h3>
 * <ol>
 *   <li><b>熔断挡掉的请求绝不重试。</b>进门先过熔断：OPEN 期被快速拒绝的请求直接返回
 *       {@link Outcome.CircuitOpen}，<b>不</b>进入重试循环——否则熔断期的每个请求都重试 N 次，
 *       等于把本该被削掉的量放大 N 倍，熔断就白做了。重试只发生在「真正打到上游、且上游病了」之后。</li>
 *   <li><b>请求头只准备一次，重放的是同一份准备结果。</b>{@link UpstreamForwarder#prepareRequest}
 *       在首发前调用一次（清洗逐跳头、清伪造身份头、补 X-Forwarded-*、写身份头、执行请求动作），
 *       后续换发复用同一 mutated 请求与同一字节请求体：补的头不会丢，请求动作也不会做两遍。</li>
 *   <li><b>总时限统一兜底。</b>整个「首发 + 各次等待 + 各发耗时」都包在一个
 *       {@link Mono#timeout(Duration)} 里，到点即停、不再补发；换发前也再核一遍剩余时间。</li>
 *   <li><b>熔断记账按「每发」真实结果。</b>每次尝试的成败如实记熔断器（4xx 计成功、
 *       5xx/连接/超时计失败），最后一发是什么就记什么，不为同一笔请求重复计数。</li>
 * </ol>
 */
@Slf4j
@Component
public class ResilientForwarder {

    private final UpstreamForwarder forwarder;
    private final CircuitBreakerRegistry registry;
    private final ResilienceProperties properties;

    public ResilientForwarder(UpstreamForwarder forwarder,
                              CircuitBreakerRegistry registry,
                              ResilienceProperties properties) {
        this.forwarder = forwarder;
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * 编排终局：
     * <ul>
     *   <li>{@link Completed}：响应（成功 2xx/3xx/4xx，或重试耗尽后兜底的真实 5xx）已交回调用方，
     *       或 5xx 终局以缓存体形式带回，由过滤器统一写一次；</li>
     *   <li>{@link Failed}：始终没拿到可用响应，带最后一次传输故障，过滤器合成 502/504；</li>
     *   <li>{@link CircuitOpen}：熔断 OPEN 期被快速拒绝，没打上游，带 Retry-After 建议毫秒。</li>
     * </ul>
     */
    public sealed interface Outcome {
        /** 成功响应（2xx/3xx/4xx）已由发送器在持有连接时就地流式写回。 */
        record Completed() implements Outcome {
        }

        /** 重试耗尽后的真实上游 5xx（已缓存，未写回）：过滤器原样写回一次。 */
        record UpstreamFailureResponse(int statusCode, HttpHeaders headers,
                                       BufferedUpstreamResponse body) implements Outcome {
        }

        record Failed(Throwable error) implements Outcome {
        }

        record CircuitOpen(long retryAfterMillis) implements Outcome {
        }
    }

    /**
     * 执行一次（可能含重试的）上游调用。
     *
     * @param cbPolicy       该路由熔断策略；null = 不熔断
     * @param retryPolicy    该路由重试策略；null = 不重试
     * @param writeResponse  成功响应的就地写回动作（仅在 2xx/3xx/4xx 那发调用一次）
     */
    public Mono<Outcome> execute(String routeNo,
                                 ServerHttpRequest incoming,
                                 String traceId,
                                 String targetUpstream,
                                 URI targetUri,
                                 GatewayRoute route,
                                 OutboundAuth auth,
                                 CircuitBreakerPolicy cbPolicy,
                                 RetryPolicy retryPolicy,
                                 Function<UpstreamResponse, Mono<Void>> writeResponse) {
        final CircuitBreaker breaker = cbPolicy == null
                ? null : registry.obtain(routeNo, targetUpstream, cbPolicy);

        // 1) 进门先过熔断。OPEN 拒绝直接返回，绝不重试（不放大熔断期流量）。
        if (breaker != null) {
            CircuitBreaker.Admission admission = breaker.admit();
            if (!admission.allowed()) {
                log.debug("熔断打开，快速拒绝 route={} upstream={} traceId={}",
                        routeNo, targetUpstream, traceId);
                return Mono.just(new Outcome.CircuitOpen(admission.retryAfterMillis()));
            }
            return run(breaker, admission.trial(), routeNo, incoming, traceId, targetUri,
                    route, auth, retryPolicy, writeResponse);
        }
        return run(null, false, routeNo, incoming, traceId, targetUri, route, auth,
                retryPolicy, writeResponse);
    }

    private Mono<Outcome> run(CircuitBreaker breaker, boolean admittedTrial, String routeNo,
                              ServerHttpRequest incoming, String traceId, URI targetUri,
                              GatewayRoute route, OutboundAuth auth, RetryPolicy retryPolicy,
                              Function<UpstreamResponse, Mono<Void>> writeResponse) {
        boolean retryEnabled = retryPolicy != null && retryPolicy.getMaxAttempts() != null
                && retryPolicy.getMaxAttempts() > 1;
        int maxAttempts = retryEnabled
                ? Math.min(retryPolicy.getMaxAttempts(), properties.maxAttemptsHardCap()) : 1;

        // 熔断没开 且 不重试：完全走老的单发双向流式链路（不缓冲请求体/响应体，行为零变化）。
        // 熔断开着时哪怕不重试，也要走「可看状态码」的尝试路径，否则纯 POST 的 5xx 风暴
        // （POST 不可重试、走流式看不到状态码）永远扳不开熔断。
        if (breaker == null && maxAttempts <= 1) {
            return singleStreamingAttempt(breaker, admittedTrial, route, incoming, traceId,
                    targetUri, auth, writeResponse);
        }

        // 只熔断不重试时也走缓冲尝试路径（为了在写回前看到状态码、正确计 5xx）：
        // 用一发的策略占位，attemptLoop 自然不会换发。
        RetryPolicy effective = retryEnabled ? retryPolicy : singleShotPolicy();

        // 可重试（或熔断需看状态码）：头只准备一次，请求体一次聚合（重放同一份字节）
        ServerHttpRequest prepared = forwarder.prepareRequest(route, incoming, traceId, auth);
        Mono<Outcome> chain = forwarder
                .aggregateRequestBody(incoming, properties.retryableRequestBodyCapBytes())
                .flatMap(agg -> {
                    if (!agg.retryable()) {
                        // 请求体超过可重放上限：放弃重试资格，退回单发流式（不缓存大请求体）。
                        // 熔断记账退化为「连接异常计失败、能应答计成功」（与无熔断流式口径一致）。
                        log.debug("请求体超过可重放上限，本笔不重试 route={} traceId={}", routeNo, traceId);
                        return singleStreamingAttempt(breaker, admittedTrial, route, incoming,
                                traceId, targetUri, auth, writeResponse);
                    }
                    return attemptLoop(breaker, admittedTrial, route, prepared, agg.bytes(),
                            traceId, targetUri, maxAttempts, effective,
                            properties.retryableResponseBodyCapBytes(), writeResponse,
                            System.nanoTime(), 1);
                });

        // 总时限统一兜底：重试路径用路由配的总时限；只熔断不重试时不另加网关卡点
        // （单次尝试本就受上游客户端 connect/read 超时约束，避免两套超时互相干扰）
        if (retryEnabled) {
            chain = chain.timeout(Duration.ofMillis(retryPolicy.getTotalTimeoutMs()))
                    .onErrorResume(java.util.concurrent.TimeoutException.class,
                            e -> Mono.just(new Outcome.Failed(e)));
        }
        return chain;
    }

    /** 只熔断、不重试时用于「缓冲尝试路径」的一发占位策略（永不换发）。 */
    private static RetryPolicy singleShotPolicy() {
        RetryPolicy r = new RetryPolicy();
        r.setMaxAttempts(1);
        r.setBackoffMs(0L);
        r.setTotalTimeoutMs(Long.MAX_VALUE / 2);
        return r;
    }

    /** 单发、双向流式；传输故障记熔断失败，正常完成（含透传的 5xx/4xx）记成功。 */
    private Mono<Outcome> singleStreamingAttempt(CircuitBreaker breaker, boolean trial,
                                                 GatewayRoute route, ServerHttpRequest incoming,
                                                 String traceId, URI targetUri, OutboundAuth auth,
                                                 Function<UpstreamResponse, Mono<Void>> writeResponse) {
        return forwarder.forward(route, incoming, traceId, targetUri, auth, writeResponse)
                .then(Mono.fromRunnable(() -> {
                    if (breaker != null) {
                        // 流式路径在写回前不缓冲、看不到状态码：能完整写回即按「上游在正常应答」记账。
                        // 连接/读取中途失败会走 onErrorResume，按失败记账。
                        breaker.recordSuccess(trial);
                    }
                }))
                .thenReturn((Outcome) new Outcome.Completed())
                .onErrorResume(err -> {
                    if (breaker != null) {
                        breaker.recordFailure(trial);
                    }
                    return Mono.just(new Outcome.Failed(err));
                });
    }

    /**
     * 递归尝试。第 attempt 发：
     * - 2xx/3xx/4xx：发送器已就地流式写回 → Completed；
     * - 5xx / 连接超时：按熔断记失败，能再发且总时限没到 → 等 backoff 换发；
     * - 否则：5xx 以缓存体带回（过滤器写回真实码），连接/超时返回 Failed（合成 502/504）。
     */
    private Mono<Outcome> attemptLoop(CircuitBreaker breaker, boolean firstIsTrial, GatewayRoute route,
                                      ServerHttpRequest prepared, byte[] bodyBytes, String traceId,
                                      URI targetUri, int maxAttempts, RetryPolicy retryPolicy,
                                      long responseCapBytes,
                                      Function<UpstreamResponse, Mono<Void>> writeResponse,
                                      long startNano, int attempt) {
        boolean trial = firstIsTrial && attempt == 1;
        return forwarder.attemptBuffered(route, prepared, bodyBytes, targetUri, responseCapBytes,
                        writeResponse)
                .flatMap(result -> dispatch(breaker, trial, route, prepared, bodyBytes, traceId,
                        targetUri, maxAttempts, retryPolicy, responseCapBytes, writeResponse,
                        startNano, attempt, result));
    }

    private Mono<Outcome> dispatch(CircuitBreaker breaker, boolean trial, GatewayRoute route,
                                   ServerHttpRequest prepared, byte[] bodyBytes, String traceId,
                                   URI targetUri, int maxAttempts, RetryPolicy retryPolicy,
                                   long responseCapBytes,
                                   Function<UpstreamResponse, Mono<Void>> writeResponse,
                                   long startNano, int attempt,
                                   UpstreamForwarder.AttemptResult result) {
        if (result instanceof UpstreamForwarder.AttemptResult.Terminal) {
            if (breaker != null) {
                breaker.recordSuccess(trial);
            }
            return Mono.just(new Outcome.Completed());
        }

        Throwable err = null;
        int failStatus = -1;
        HttpHeaders failHeaders = null;
        BufferedUpstreamResponse failBody = null;
        if (result instanceof UpstreamForwarder.AttemptResult.FailureStatus fs) {
            failStatus = fs.statusCode();
            failHeaders = fs.headers();
            failBody = fs.buffered();
        } else if (result instanceof UpstreamForwarder.AttemptResult.TransportError te) {
            err = te.error();
        }

        if (breaker != null) {
            breaker.recordFailure(trial);
        }

        // 只有「上游病了」才换发：连接/IO/超时；5xx 但错误体超大则不再换发
        boolean retriable = err == null
                ? !failBody.oversized()
                : UpstreamOutcomes.isTransportFailure(err);
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
        long remainingAfterBackoff = retryPolicy.getTotalTimeoutMs() - elapsedMs
                - retryPolicy.getBackoffMs();
        boolean canRetry = attempt < maxAttempts && retriable && remainingAfterBackoff > 0;

        if (!canRetry) {
            if (err != null) {
                return Mono.just(new Outcome.Failed(err));
            }
            // 真实 5xx 终局：缓存体交过滤器原样写回（状态码/头/体都是上游的）
            return Mono.just(new Outcome.UpstreamFailureResponse(failStatus, failHeaders, failBody));
        }

        log.debug("上游失败，准备第 {} 次换发 route={} traceId={} status={} err={}",
                attempt + 1, route.getRouteNo(), traceId, failStatus,
                err == null ? "-" : err.toString());
        return Mono.delay(Duration.ofMillis(retryPolicy.getBackoffMs()))
                .then(Mono.defer(() -> attemptLoop(breaker, false, route, prepared, bodyBytes,
                        traceId, targetUri, maxAttempts, retryPolicy, responseCapBytes,
                        writeResponse, startNano, attempt + 1)));
    }
}
