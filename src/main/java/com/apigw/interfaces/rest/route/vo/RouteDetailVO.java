package com.apigw.interfaces.rest.route.vo;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GrayGroup;
import com.apigw.domain.route.GatewayRule;
import com.apigw.infrastructure.store.RouteStore;

import java.io.Serializable;
import java.util.List;

/**
 * 路由详情视图：带回全部匹配条件与转发动作，子项按顺序号排好；
 * 配了灰度时带回灰度分组（配置顺序即权重轮询的稳定次序）。
 */
public record RouteDetailVO(String id,
                            String routeNo,
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

    public record GrayGroupVO(String groupName,
                              String upstream,
                              Integer weight,
                              List<String> tags) implements Serializable {
    }

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

    public static RouteDetailVO of(GatewayRoute r) {
        return new RouteDetailVO(
                r.getId(),
                r.getRouteNo(),
                r.getName(),
                r.getUpstream(),
                r.getEnabled(),
                r.getAuthRequired(),
                r.getRemark(),
                r.getVersion(),
                RouteStore.sorted(r.getConditions()).stream().map(RouteDetailVO::toRuleVo).toList(),
                RouteStore.sorted(r.getActions()).stream().map(RouteDetailVO::toRuleVo).toList(),
                r.getGrayGroups() == null ? List.of()
                        : r.getGrayGroups().stream().map(RouteDetailVO::toGroupVo).toList(),
                r.getResilience() == null ? null : toResilienceVo(r.getResilience()));
    }

    private static RuleVO toRuleVo(GatewayRule g) {
        return new RuleVO(g.getStage(), g.getType(), g.getName(), g.getValue(), g.getSortNo());
    }

    private static GrayGroupVO toGroupVo(GrayGroup g) {
        return new GrayGroupVO(g.getGroupName(), g.getUpstream(), g.getWeight(),
                g.getTags() == null ? List.of() : List.copyOf(g.getTags()));
    }

    private static ResilienceVO toResilienceVo(
            com.apigw.domain.route.ResiliencePolicy p) {
        CircuitBreakerVO cb = p.getCircuitBreaker() == null ? null
                : new CircuitBreakerVO(
                        p.getCircuitBreaker().getWindowSize(),
                        p.getCircuitBreaker().getMinimumNumberOfCalls(),
                        p.getCircuitBreaker().getFailureRateThreshold(),
                        p.getCircuitBreaker().getMinFailureCount(),
                        p.getCircuitBreaker().getOpenWaitMs(),
                        p.getCircuitBreaker().getTrialFraction(),
                        p.getCircuitBreaker().getSuccessThreshold());
        RetryVO rt = p.getRetry() == null ? null
                : new RetryVO(
                        p.getRetry().getMaxAttempts(),
                        p.getRetry().getBackoffMs(),
                        p.getRetry().getTotalTimeoutMs(),
                        p.getRetry().getIdempotentMethods() == null
                                ? List.of() : List.copyOf(p.getRetry().getIdempotentMethods()),
                        p.getRetry().getIdempotencyKeyHeader());
        return new ResilienceVO(p.getCircuitBreakerEnabled(), cb, p.getRetryEnabled(), rt);
    }
}
