package com.apigw.interfaces.rest.route.vo;

import com.apigw.proxy.match.RouteMatchExplainer;

import java.io.Serializable;
import java.util.List;

/**
 * 路由落点排查结果视图。字段与 {@link RouteMatchExplainer.MatchExplanation} 一一对应，
 * 控制层只做协议映射，排查逻辑在领域/proxy 侧。
 */
public record RouteMatchDebugResponse(String method,
                                      String path,
                                      String matchedRouteNo,
                                      long snapshotRevision,
                                      List<RouteVerdictVO> matchedRoutes,
                                      List<RouteVerdictVO> unmatchedRoutes,
                                      String summary) implements Serializable {

    public record RouteVerdictVO(String routeNo,
                                 String name,
                                 String upstream,
                                 boolean enabled,
                                 int conditionCount,
                                 int pathPrefixLength,
                                 List<ConditionVerdictVO> conditions,
                                 int rank,
                                 boolean matched,
                                 String reason) implements Serializable {
    }

    public record ConditionVerdictVO(String type,
                                     String name,
                                     String value,
                                     Integer sortNo,
                                     boolean matched,
                                     String reason) implements Serializable {
    }

    public static RouteMatchDebugResponse of(RouteMatchExplainer.MatchExplanation e) {
        return new RouteMatchDebugResponse(
                e.method(), e.path(), e.matchedRouteNo(), e.snapshotRevision(),
                e.matchedRoutes().stream().map(RouteMatchDebugResponse::toRoute).toList(),
                e.unmatchedRoutes().stream().map(RouteMatchDebugResponse::toRoute).toList(),
                e.summary());
    }

    private static RouteVerdictVO toRoute(RouteMatchExplainer.RouteVerdict v) {
        return new RouteVerdictVO(v.routeNo(), v.name(), v.upstream(), v.enabled(),
                v.conditionCount(), v.pathPrefixLength(),
                v.conditions().stream().map(RouteMatchDebugResponse::toCondition).toList(),
                v.rank(), v.matched(), v.reason());
    }

    private static ConditionVerdictVO toCondition(RouteMatchExplainer.ConditionVerdict c) {
        return new ConditionVerdictVO(c.type(), c.name(), c.value(), c.sortNo(),
                c.matched(), c.reason());
    }
}
