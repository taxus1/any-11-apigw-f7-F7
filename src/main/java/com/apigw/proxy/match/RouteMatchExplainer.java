package com.apigw.proxy.match;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 路由「落点排查」：给一笔请求（路径/方法/头/查询参数），算出它最终会走哪条路由，并说清为什么。
 *
 * 与转发同口径（这是它能用于线上自查的前提）：
 * - 候选集就是转发快照——只含「启用且带条件」的路由，停用/无条件路由不参与（它们转发时也接不到流量）；
 * - 每条路由逐条件判定用的就是 {@link RouteMatcher#conditionMatches}，命中=全部条件 AND；
 * - 赢家与名次用的就是 {@link RouteMatcher#PRECEDENCE}。
 * 因此这里算出的赢家与 {@link RouteMatcher#match} 必然一致；一旦不一致，属于实现 bug。
 *
 * 输出里：
 * - matchedRoutes：所有「全部条件成立」的路由，按定序从先到后排，带 rank（从 1 起）与定序依据；
 *   rank=1 的就是赢家，其余是「也命中、但被更靠前的抢走」的路由；
 * - unmatchedRoutes：至少一条条件不成立的路由，逐条列出哪条没过、为什么，按编号稳定排序。
 * 结论顺序只依赖路由自身属性，不受配置遍历顺序影响，可重复核对。
 *
 * 无状态、不碰 Redis；快照与请求由上层传入，方便直接单测。
 */
public final class RouteMatchExplainer {

    private static final RouteMatcher MATCHER = new RouteMatcher();

    private RouteMatchExplainer() {
    }

    public static MatchExplanation explain(List<GatewayRoute> routes, MatchableRequest request) {
        return explain(0, routes, request);
    }

    /**
     * @param snapshotRevision 参与判定的路由快照版本号（排查结果里回显，便于和生效版本对账）
     */
    public static MatchExplanation explain(long snapshotRevision,
                                           List<GatewayRoute> routes, MatchableRequest request) {
        // 暂存「路由 → 判定」，排序直接用路由对象，输出记录里不带领域聚合
        List<Evaluated> matched = new ArrayList<>();
        List<Evaluated> unmatched = new ArrayList<>();

        for (GatewayRoute route : routes) {
            List<ConditionVerdict> verdicts = new ArrayList<>();
            boolean allMatch = true;
            for (GatewayRule c : sorted(route.getConditions())) {
                boolean ok = MATCHER.conditionMatches(c, request);
                allMatch &= ok;
                verdicts.add(new ConditionVerdict(
                        c.getType(), c.getName(), c.getValue(), c.getSortNo(), ok,
                        ok ? "条件成立" : reason(c, request)));
            }
            Evaluated evaluated = new Evaluated(route, List.copyOf(verdicts));
            (allMatch ? matched : unmatched).add(evaluated);
        }

        // 命中的按转发定序排——第一名即真实赢家；不命中的按编号稳定排，输出可复现
        matched.sort(Comparator.comparing(Evaluated::route, RouteMatcher.PRECEDENCE));
        unmatched.sort(Comparator.comparing(e -> e.route().getRouteNo()));

        GatewayRoute winner = matched.isEmpty() ? null : matched.get(0).route();
        List<RouteVerdict> ranked = new ArrayList<>();
        for (int i = 0; i < matched.size(); i++) {
            int rank = i + 1;
            ranked.add(toVerdict(matched.get(i), rank,
                    winReason(matched.get(i).route(), rank, winner)));
        }
        List<RouteVerdict> notMatched = unmatched.stream()
                .map(e -> toVerdict(e, 0, null))
                .toList();

        return new MatchExplanation(
                request.method(), request.path(),
                winner == null ? null : winner.getRouteNo(),
                snapshotRevision,
                List.copyOf(ranked), notMatched,
                summary(request, winner, matched.size(), unmatched.size()));
    }

    private static RouteVerdict toVerdict(Evaluated e, int rank, String reason) {
        GatewayRoute r = e.route();
        return new RouteVerdict(r.getRouteNo(), r.getName(), r.getUpstream(),
                Integer.valueOf(1).equals(r.getEnabled()),
                r.getConditions().size(), RouteMatcher.pathPrefixLength(r),
                e.conditions(), rank, rank > 0, reason);
    }

    /** 赢家/被抢的人类可读理由：只解释定序是怎么排的。 */
    private static String winReason(GatewayRoute r, int rank, GatewayRoute winner) {
        String basis = "路径前缀长度 " + RouteMatcher.pathPrefixLength(r)
                + "、条件数 " + r.getConditions().size() + "、编号 " + r.getRouteNo();
        if (rank == 1) {
            return "命中的路由里它定序最靠前（" + basis
                    + "），所以最终走它；规则：前缀更长优先 → 条件更多优先 → 编号字典序";
        }
        return "它全部条件也成立，但定序排在 " + winner.getRouteNo()
                + " 之后（" + basis + "），被更靠前的路由抢走";
    }

    private static String summary(MatchableRequest req, GatewayRoute winner,
                                  int matchedCount, int unmatchedCount) {
        if (winner == null) {
            return "请求 " + req.method() + " " + req.path()
                    + " 没有任何一条路由的全部条件同时成立（命中 0 条，未命中 " + unmatchedCount
                    + " 条），转发时将回 404 NO_ROUTE";
        }
        return "请求 " + req.method() + " " + req.path() + " 最终命中路由 " + winner.getRouteNo()
                + "（全部 " + matchedCount + " 条命中路由中定序第 1），上游 " + winner.getUpstream();
    }

    /** 单条条件不成立时给出具体原因（成立时统一给「条件成立」）。 */
    private static String reason(GatewayRule c, MatchableRequest request) {
        return switch (c.getType()) {
            case RuleTypes.TYPE_PATH_PREFIX ->
                    "路径前缀不匹配：规则 " + c.getValue() + "，实际路径 " + request.path()
                            + "（前缀需在路径段边界上重合，/order 不等于 /ordering）";
            case RuleTypes.TYPE_METHOD ->
                    "方法不匹配：要求 " + RouteMatcher.normalizeMethod(c.getValue())
                            + "，实际 " + (request.method() == null ? "(无)" : request.method());
            case RuleTypes.TYPE_HEADER -> {
                String actual = request.header(c.getName());
                yield "请求头不匹配：要求头 " + c.getName() + "=" + c.getValue()
                        + "（头名不区分大小写、值区分），实际 "
                        + (actual == null ? "未携带该头" : c.getName() + "=" + actual);
            }
            case RuleTypes.TYPE_QUERY -> {
                List<String> actual = request.queryParams(c.getName());
                yield "查询参数不匹配：要求 URL 上 " + c.getName() + "=" + c.getValue()
                        + "（参数名/值都区分大小写，且只认查询串不认请求体），实际 "
                        + (actual == null ? "无此参数" : c.getName() + "=" + actual);
            }
            default -> "未知条件类型：" + c.getType() + "（按不命中处理）";
        };
    }

    private static List<GatewayRule> sorted(List<GatewayRule> rules) {
        List<GatewayRule> copy = new ArrayList<>(rules);
        copy.sort(Comparator.comparing(GatewayRule::getSortNo,
                Comparator.nullsLast(Integer::compareTo)));
        return copy;
    }

    /** 遍历阶段的内部暂存：路由本体 + 它的逐条条件结论。 */
    private record Evaluated(GatewayRoute route, List<ConditionVerdict> conditions) {
    }

    /**
     * 一条路由对这笔请求的判定结果。
     *
     * @param routeNo          路由编号
     * @param name             路由名称
     * @param upstream         转发上游
     * @param enabled          是否启用（排查候选集里恒为 true，字段保留以备扩展）
     * @param conditionCount   条件总数（定序第二依据）
     * @param pathPrefixLength 路径前缀长度（定序第一依据）
     * @param conditions       逐条条件判定（按顺序号）
     * @param rank             在命中路由里的名次，从 1 起；未命中为 0
     * @param matched          是否全部条件成立
     * @param reason           命中时解释定序名次；未命中为 null（逐条原因看 conditions）
     */
    public record RouteVerdict(String routeNo, String name, String upstream, boolean enabled,
                               int conditionCount, int pathPrefixLength,
                               List<ConditionVerdict> conditions,
                               int rank, boolean matched, String reason) {
    }

    /** 单条条件判定：type/name/value 是配置，matched+reason 是结论。 */
    public record ConditionVerdict(String type, String name, String value, Integer sortNo,
                                   boolean matched, String reason) {
    }

    /** 整笔请求的排查结论。 */
    public record MatchExplanation(String method, String path, String matchedRouteNo,
                                   long snapshotRevision,
                                   List<RouteVerdict> matchedRoutes,
                                   List<RouteVerdict> unmatchedRoutes,
                                   String summary) {
    }
}
