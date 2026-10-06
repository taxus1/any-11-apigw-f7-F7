package com.apigw.interfaces.rest.route.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 保存路由的请求体。
 *
 * 用 record 承载外部输入：字段与题目描述一一对应，不做多余包装。
 * conditions / actions 里每条对应一个 {@link RuleVO}；
 * grayGroups 可空：不传或传空数组 = 这条路由不做灰度，所有请求打主上游 upstream。
 */
public record RouteSaveVO(String routeNo,
                          String name,
                          String upstream,
                          Integer enabled,
                          Integer authRequired,
                          String remark,
                          Integer version,
                          List<RuleVO> conditions,
                          List<RuleVO> actions,
                          List<GrayGroupVO> grayGroups,
                          ResilienceVO resilience) implements Serializable {

    public record RuleVO(String stage,
                         String type,
                         String name,
                         String value,
                         Integer sortNo) implements Serializable {
    }

    /**
     * 一个灰度分组：
     * weight 0~100 整数，各组之和必须恰好为 100；
     * tags 为该组认领的灰度标记值（精确、大小写敏感），可空表示该组只按权重接量。
     */
    public record GrayGroupVO(String groupName,
                              String upstream,
                              Integer weight,
                              List<String> tags) implements Serializable {
    }

    /**
     * 韧性配置：熔断、重试各自独立开关（1 开 / 0 关，缺省 0），整块不传 = 两者都不开。
     * 子项只传想配的，其余在领域层取默认值。
     */
    public record ResilienceVO(Integer circuitBreakerEnabled,
                               CircuitBreakerVO circuitBreaker,
                               Integer retryEnabled,
                               RetryVO retry) implements Serializable {
    }

    public record CircuitBreakerVO(Integer windowSize,
                                   Integer minimumNumberOfCalls,
                                   Integer failureRateThreshold,
                                   Integer minFailureCount,
                                   Long openWaitMs,
                                   Integer trialFraction,
                                   Integer successThreshold) implements Serializable {
    }

    public record RetryVO(Integer maxAttempts,
                          Long backoffMs,
                          Long totalTimeoutMs,
                          List<String> idempotentMethods,
                          String idempotencyKeyHeader) implements Serializable {
    }
}
