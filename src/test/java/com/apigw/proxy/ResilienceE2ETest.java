package com.apigw.proxy;

import com.apigw.domain.route.CircuitBreakerPolicy;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.ResiliencePolicy;
import com.apigw.domain.route.RetryPolicy;
import com.apigw.proxy.accesslog.AccessLogRecorder;
import com.apigw.proxy.config.GatewayProxyProperties;
import com.apigw.proxy.config.ResilienceProperties;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.resilience.CircuitBreakerRegistry;
import com.apigw.proxy.resilience.ResilientForwarder;
import com.apigw.proxy.route.RouteCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断 × 重试端到端测试（真实 Netty + 真实 WebClient + JDK 假上游，无 Redis）。
 *
 * 覆盖题目硬要求：
 * - 重试只对安全方法/约定幂等键生效：GET 遇 5xx 重试到成功；POST 默认一发都不重试；
 * - 4xx 不触发熔断、不重试；
 * - 连续失败到门槛后熔断，OPEN 期请求快速 503 且不打上游，带 Retry-After；
 * - 歇够后半开试探，试探成功恢复放量；
 * - 熔断挡掉的请求不走进重试（不放大流量）；
 * - 重试耗尽照实返回真实失败（5xx 原样透传）。
 */
class ResilienceE2ETest {

    private FakeUpstream upstream;
    private InMemoryRouteStore store;
    private RouteCatalog catalog;
    private CircuitBreakerRegistry breakerRegistry;
    private DisposableServer server;
    private String baseUrl;
    private WebClient client;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new FakeUpstream();
        store = new InMemoryRouteStore();
        var props = new GatewayProxyProperties(
                Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofHours(1));
        catalog = new RouteCatalog(store, props);
        breakerRegistry = new CircuitBreakerRegistry();

        var nettyClient = HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofSeconds(3))
                .doOnConnected(c -> c.addHandlerLast(
                        new io.netty.handler.timeout.ReadTimeoutHandler(
                                3, java.util.concurrent.TimeUnit.SECONDS)));
        WebClient webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(nettyClient))
                .build();
        UpstreamForwarder forwarder = new UpstreamForwarder(webClient);
        var resilient = new ResilientForwarder(forwarder, breakerRegistry,
                new ResilienceProperties(1024 * 1024L, 2 * 1024 * 1024L, 5));

        var filter = new GatewayProxyWebFilter(
                catalog, new RouteMatcher(), forwarder, resilient,
                new AccessLogRecorder(), e -> { }, new ObjectMapper(),
                new com.apigw.proxy.userauth.UserAuthGatekeeper(null, null),
                new com.apigw.proxy.gray.GrayReleaseSelector(
                        new com.apigw.proxy.gray.GrayProperties(null)));

        WebHandler tail = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        WebHandler filtering = new FilteringWebHandler(tail, List.of(filter));
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(filtering);
        adapter.afterPropertiesSet();
        HttpHandler httpHandler = adapter;
        server = HttpServer.create().handle(new ReactorHttpHandlerAdapter(httpHandler)).bindNow();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = WebClient.builder().build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
        upstream.close();
    }

    private GatewayRule cond(String value, int sort) {
        return GatewayRule.create("REQUEST", "PATH_PREFIX", null, value, sort);
    }

    private RetryPolicy retry(int attempts, long backoffMs, long totalMs) {
        RetryPolicy r = new RetryPolicy();
        r.setMaxAttempts(attempts);
        r.setBackoffMs(backoffMs);
        r.setTotalTimeoutMs(totalMs);
        return r;
    }

    private CircuitBreakerPolicy cb(int minFailureCount, long openWaitMs) {
        CircuitBreakerPolicy c = new CircuitBreakerPolicy();
        c.setWindowSize(10);
        c.setMinimumNumberOfCalls(1);
        c.setFailureRateThreshold(100);
        c.setMinFailureCount(minFailureCount);
        c.setOpenWaitMs(openWaitMs);
        c.setTrialFraction(100);
        c.setSuccessThreshold(1);
        return c;
    }

    private void load(String no, String prefix, ResiliencePolicy resilience) {
        GatewayRoute r = GatewayRoute.create(no, no, upstream.baseUrl(), 1, null);
        r.replaceRules(List.of(cond(prefix, 1)), List.of());
        if (resilience != null) {
            r.replaceResilience(resilience);
        }
        store.setRoutes(List.of(r));
        catalog.refresh().block();
    }

    private ResiliencePolicy withRetry(RetryPolicy r) {
        ResiliencePolicy p = new ResiliencePolicy();
        p.setCircuitBreakerEnabled(0);
        p.setRetryEnabled(1);
        p.setRetry(r);
        return p;
    }

    private ResiliencePolicy withCb(CircuitBreakerPolicy c) {
        ResiliencePolicy p = new ResiliencePolicy();
        p.setCircuitBreakerEnabled(1);
        p.setCircuitBreaker(c);
        p.setRetryEnabled(0);
        return p;
    }

    // ---- 重试 ----

    @Test
    void get_retriesOn5xx_andSucceedsOnSecondAttempt() {
        upstream.scriptStatuses(500, 200);
        load("r", "/r/", withRetry(retry(2, 10, 5000)));

        var resp = client.get().uri(baseUrl + "/r/1").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();
        // 首发 500 + 一次重试 200 = 打了上游两次
        assertThat(upstream.hitCount()).isEqualTo(2);
    }

    @Test
    void post_isNeverRetried_evenWhenUpstreamReturns5xx() {
        upstream.scriptStatuses(500, 200);
        load("p", "/p/", withRetry(retry(3, 10, 5000)));

        var resp = client.post().uri(baseUrl + "/p/create").bodyValue("{}").exchange().block();
        // 500 原样透传，且只打了一发（没有重试，避免重复下单/扣款）
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        resp.releaseBody().block();
        assertThat(upstream.hitCount()).isEqualTo(1);
    }

    @Test
    void post_withIdempotencyKey_isRetried() {
        RetryPolicy r = retry(2, 10, 5000);
        r.setIdempotencyKeyHeader("Idempotency-Key");
        upstream.scriptStatuses(500, 200);
        load("ik", "/ik/", withRetry(r));

        var resp = client.post().uri(baseUrl + "/ik/order")
                .header("Idempotency-Key", "biz-uuid-123")
                .bodyValue("{}").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();
        assertThat(upstream.hitCount()).isEqualTo(2);
    }

    @Test
    void client4xx_isNotRetried_andPassedThrough() {
        upstream.scriptStatuses(400);
        load("bad", "/bad/", withRetry(retry(3, 10, 5000)));

        var resp = client.get().uri(baseUrl + "/bad/1").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        resp.releaseBody().block();
        // 4xx 是请求自己的问题：不重试
        assertThat(upstream.hitCount()).isEqualTo(1);
    }

    @Test
    void retryExhausted_returnsRealUpstreamFailure() {
        upstream.setResponseStatus(503);
        load("down", "/down/", withRetry(retry(3, 10, 5000)));

        var resp = client.get().uri(baseUrl + "/down/1").exchange().block();
        // 重试全失败：照实返回上游的 503，共打 3 发
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        resp.releaseBody().block();
        assertThat(upstream.hitCount()).isEqualTo(3);
    }

    @Test
    void totalTimeout_capsRetries() {
        // 总时限 100ms、间隔 200ms：首发失败后来不及补发，只打一发
        upstream.setResponseStatus(500);
        load("tt", "/tt/", withRetry(retry(3, 200, 100)));

        var resp = client.get().uri(baseUrl + "/tt/1").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        resp.releaseBody().block();
        assertThat(upstream.hitCount()).isEqualTo(1);
    }

    // ---- 熔断 ----

    @Test
    void circuitOpens_afterThreshold_thenFastFailsWithoutHittingUpstream() {
        load("cb", "/cb/", withCb(cb(3, 30_000)));
        upstream.setResponseStatus(500);

        // 熔断路径：这些请求是 POST（不重试），每发真实打一次 500
        for (int i = 0; i < 3; i++) {
            var r = client.post().uri(baseUrl + "/cb/x").bodyValue("{}").exchange().block();
            r.releaseBody().block();
        }
        int hitsAtTrip = upstream.hitCount();

        // 第 4 笔：熔断已 OPEN，直接 503，不再打上游
        var rejected = client.get().uri(baseUrl + "/cb/y").exchange().block();
        assertThat(rejected.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rejected.headers().asHttpHeaders().getFirst("X-Gateway-Error"))
                .isEqualTo("UPSTREAM_CIRCUIT_OPEN");
        assertThat(rejected.headers().asHttpHeaders().getFirst("Retry-After")).isNotBlank();
        String body = rejected.bodyToMono(String.class).block();
        assertThat(body).contains("UPSTREAM_CIRCUIT_OPEN");
        // 上游命中数不变：熔断请求没打上游
        assertThat(upstream.hitCount()).isEqualTo(hitsAtTrip);
    }

    @Test
    void fourxx_doesNotTripBreaker() {
        load("ok", "/ok/", withCb(cb(2, 30_000)));
        upstream.setResponseStatus(404);

        for (int i = 0; i < 10; i++) {
            var r = client.get().uri(baseUrl + "/ok/" + i).exchange().block();
            assertThat(r.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            r.releaseBody().block();
        }
        // 一批 4xx 后仍正常打上游，没有熔断（没有 503）
        var again = client.get().uri(baseUrl + "/ok/z").exchange().block();
        assertThat(again.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        again.releaseBody().block();
        assertThat(upstream.hitCount()).isEqualTo(11);
    }

    @Test
    void circuitOpensThenRecoversAfterWaitAndProbe() throws Exception {
        // 等待时长设短一点，测试可承受
        load("rec", "/rec/", withCb(cb(2, 300)));
        upstream.setResponseStatus(500);

        for (int i = 0; i < 2; i++) {
            client.post().uri(baseUrl + "/rec/x").bodyValue("{}").exchange().block()
                    .releaseBody().block();
        }
        // 熔断打开
        assertThat(client.get().uri(baseUrl + "/rec/z").exchange().block().statusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // 歇一会儿（>openWait），把上游恢复成 200
        Thread.sleep(450);
        upstream.setResponseStatus(200);

        // 下一笔是半开试探：成功 → 熔断恢复放量
        var probe = client.get().uri(baseUrl + "/rec/probe").exchange().block();
        assertThat(probe.statusCode()).isEqualTo(HttpStatus.OK);
        probe.releaseBody().block();

        var after = client.get().uri(baseUrl + "/rec/after").exchange().block();
        assertThat(after.statusCode()).isEqualTo(HttpStatus.OK);
        after.releaseBody().block();
    }

    @Test
    void circuitOpenRequest_isNotRetried() {
        // 熔断 + 重试都开：熔断 OPEN 后的请求直接 503，绝不重试 N 次放大流量
        RetryPolicy r = retry(3, 5, 5000);
        ResiliencePolicy both = new ResiliencePolicy();
        both.setCircuitBreakerEnabled(1);
        both.setCircuitBreaker(cb(2, 30_000));
        both.setRetryEnabled(1);
        both.setRetry(r);
        load("both", "/both/", both);
        upstream.setResponseStatus(500);

        // 先打跳闸（GET 可重试，2 笔 × 最多 3 发都 500 也无所谓，总会跳闸）
        for (int i = 0; i < 4; i++) {
            client.get().uri(baseUrl + "/both/x").exchange().block().releaseBody().block();
        }
        int hitsAtOpen = upstream.hitCount();

        // 熔断已开：再来的 GET 直接 503，且上游命中数不增加（没重试、没打上游）
        var rejected = client.get().uri(baseUrl + "/both/y").exchange().block();
        assertThat(rejected.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        rejected.releaseBody().block();
        assertThat(upstream.hitCount()).isEqualTo(hitsAtOpen);
    }

    @Test
    void retriesStillCountFailuresTowardBreaker() {
        // 重试 + 熔断：GET 每次首发 5xx 会重试，但每次尝试的失败也喂给熔断统计，
        // 连续打下去熔断照样会开（重试不会让熔断「看不到」失败）
        RetryPolicy r = retry(2, 5, 5000);
        ResiliencePolicy both = new ResiliencePolicy();
        both.setCircuitBreakerEnabled(1);
        both.setCircuitBreaker(cb(4, 30_000));
        both.setRetryEnabled(1);
        both.setRetry(r);
        load("rc", "/rc/", both);
        upstream.setResponseStatus(502);

        boolean sawCircuitOpen = false;
        for (int i = 0; i < 8; i++) {
            var resp = client.get().uri(baseUrl + "/rc/" + i).exchange().block();
            if (resp.statusCode() == HttpStatus.SERVICE_UNAVAILABLE
                    && "UPSTREAM_CIRCUIT_OPEN".equals(
                    resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"))) {
                sawCircuitOpen = true;
                resp.releaseBody().block();
                break;
            }
            resp.releaseBody().block();
        }
        assertThat(sawCircuitOpen).as("持续 5xx + 重试，熔断最终仍应打开").isTrue();
    }

    @Test
    void noResilienceConfig_behavesAsBefore_singleShotStreaming() {
        // 不配 resilience：5xx 单发透传，不重试、不熔断
        load("plain", "/plain/", null);
        upstream.setResponseStatus(500);

        var resp = client.get().uri(baseUrl + "/plain/1").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        resp.releaseBody().block();
        assertThat(upstream.hitCount()).isEqualTo(1);
    }
}
