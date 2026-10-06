package com.apigw.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 韧性链路（熔断/重试）的全局兜底参数（前缀 apigw.resilience）。
 *
 * <p>这些不是「按路由开关」——按路由的门槛/次数/间隔在路由配置里；这里只放与资源保护
 * 相关、应由网关统一守住的硬边界，防止重试路径为了「可重放/可判定」而无限缓冲把内存吃光。
 *
 * @param retryableRequestBodyCapBytes  允许重试的请求，其请求体最多缓存多少字节用于重放
 *                                      （超过则该请求放弃重试资格：安全第一，直接单发）。
 *                                      默认 1 MiB。
 * @param retryableResponseBodyCapBytes 可重试请求在上游回 5xx 时，为判定/重试最多缓存多少响应体；
 *                                      超过上限的 5xx 不再重试（异常大的「错误体」按既成结果透传）。
 *                                      默认 2 MiB。
 * @param maxAttemptsHardCap            无论路由怎么配，单请求对同一上游最多打几发（含首发）的硬顶，
 *                                      配错也挡不住的时候由这里兜底，默认 5。
 */
@ConfigurationProperties(prefix = "apigw.resilience")
public record ResilienceProperties(long retryableRequestBodyCapBytes,
                                   long retryableResponseBodyCapBytes,
                                   int maxAttemptsHardCap) {

    public ResilienceProperties {
        if (retryableRequestBodyCapBytes <= 0) {
            retryableRequestBodyCapBytes = 1024 * 1024L;
        }
        if (retryableResponseBodyCapBytes <= 0) {
            retryableResponseBodyCapBytes = 2 * 1024 * 1024L;
        }
        if (maxAttemptsHardCap <= 0) {
            maxAttemptsHardCap = 5;
        }
    }
}
