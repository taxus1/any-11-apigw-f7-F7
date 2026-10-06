package com.apigw.proxy.resilience;

import com.apigw.proxy.error.UpstreamFailureKind;

/**
 * 「这笔上游结果算不算上游病了」的唯一口径，熔断计失败与重试判可重试都用它，
 * 避免两套逻辑各判各的、出现「重试了却不计失败」这种对不上的情况。
 *
 * <p>判定原则（为什么 4xx 不算）：
 * <ul>
 *   <li><b>传输层故障（连不上、连接被断、TLS 失败、超时）</b>：上游没能正常处理这笔请求，
 *       是「上游病了/网络病了」——算失败、可重试、计熔断。</li>
 *   <li><b>上游回 5xx（500/502/503/504…）</b>：服务端自己没把请求处理成，是病了——
 *       算失败、可重试、计熔断。唯一例外是 <b>501 Not Implemented</b>：那是上游明确、
 *       稳定地表示「这个方法/功能我不支持」，重试多少次结果都一样，不重试也不该计进病态。</li>
 *   <li><b>上游回 4xx</b>：是「这笔请求自己有问题」（参数错、没权限、没认证、资源不存在、
 *       甚至 401/403/404/400/422…）。上游进程活得好好的、判断也是对的，<b>一律不算失败</b>：
 *       重试不会变好，更不能因为一批坏请求把健康上游误熔断。
 *       429（上游主动限流）同理——那是上游在正常地施加背压、不是崩了；也<b>不重试</b>，
 *       否则网关重试反而把限流中的上游压得更狠。408（请求超时）是请求侧的超时语义，同样不重试。</li>
 *   <li><b>2xx / 3xx</b>：正常结果，成功。</li>
 * </ul>
 */
public final class UpstreamOutcomes {

    private UpstreamOutcomes() {
    }

    /** 传输层异常是否属于「上游病了」：拿不到任何 HTTP 响应的故障（连接/IO/超时/TLS）。 */
    public static boolean isTransportFailure(Throwable error) {
        UpstreamFailureKind kind = UpstreamFailureKind.classify(error);
        return kind == UpstreamFailureKind.UPSTREAM_UNAVAILABLE
                || kind == UpstreamFailureKind.UPSTREAM_TIMEOUT;
    }

    /** 上游给出了 HTTP 状态码时，该码是否代表「上游病了」（可重试 + 计熔断）。 */
    public static boolean isUpstreamFailureStatus(int statusCode) {
        return statusCode >= 500 && statusCode < 600 && statusCode != 501;
    }

    /** 该状态码是否代表「上游在正常工作」（2xx/3xx/4xx 都算；4xx 是请求的问题不是上游的病）。 */
    public static boolean isHealthyStatus(int statusCode) {
        return !isUpstreamFailureStatus(statusCode);
    }
}
