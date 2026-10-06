package com.apigw.proxy.match;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落点排查器测试：
 * - 赢家与 {@link RouteMatcher} 真实选择一致（同一份快照、同一套判定）；
 * - 多条命中时给出名次，被抢的路由明确说清被谁抢；
 * - 不命中路由逐条列出失败条件与原因；
 * - 与配置遍历顺序无关，结论稳定可复现；
 * - 查询参数只认 URL 查询串、名值大小写敏感。
 */
class RouteMatchExplainerTest {

    private final RouteMatcher matcher = new RouteMatcher();

    private GatewayRoute route(String no, String upstream, List<GatewayRule> conditions) {
        GatewayRoute r = GatewayRoute.create(no, no, upstream, 1, null);
        r.replaceRules(conditions, List.of());
        return r;
    }

    private GatewayRule path(String v, int sort) {
        return GatewayRule.create(null, "PATH_PREFIX", null, v, sort);
    }

    private GatewayRule method(String v, int sort) {
        return GatewayRule.create(null, "METHOD", null, v, sort);
    }

    private GatewayRule header(String name, String v, int sort) {
        return GatewayRule.create(null, "HEADER", name, v, sort);
    }

    private GatewayRule query(String name, String v, int sort) {
        return GatewayRule.create(null, "QUERY", name, v, sort);
    }

    private MatchableRequest req(String method, String path) {
        return SimpleMatchableRequest.of(method, path, null, null);
    }

    @Test
    void winnerAgreesWithRouteMatcher_andRanksOtherMatches() {
        GatewayRoute broad = route("broad", "http://h:1", List.of(path("/order/", 1)));
        GatewayRoute specific = route("specific", "http://h:2",
                List.of(path("/order/abc/", 1), method("GET", 2)));
        MatchableRequest request = req("GET", "/order/abc/x");

        RouteMatchExplainer.MatchExplanation e =
                RouteMatchExplainer.explain(List.of(broad, specific), request);

        // 排查赢家与真实匹配器一致
        assertEquals("specific", e.matchedRouteNo());
        assertEquals("specific", matcher.match(List.of(broad, specific), request).getRouteNo());

        // 两条都命中，specific 第 1、broad 第 2（被抢），各自有理由
        assertEquals(2, e.matchedRoutes().size());
        assertEquals("specific", e.matchedRoutes().get(0).routeNo());
        assertEquals(1, e.matchedRoutes().get(0).rank());
        assertEquals("broad", e.matchedRoutes().get(1).routeNo());
        assertEquals(2, e.matchedRoutes().get(1).rank());
        assertTrue(e.matchedRoutes().get(1).reason().contains("被更靠前的路由抢走"));
        assertTrue(e.matchedRoutes().get(1).reason().contains("specific"));
        assertTrue(e.unmatchedRoutes().isEmpty());
    }

    @Test
    void resultIsIndependentOfConfigOrder() {
        GatewayRoute broad = route("broad", "http://h:1", List.of(path("/order/", 1)));
        GatewayRoute specific = route("specific", "http://h:2", List.of(path("/order/abc/", 1)));
        MatchableRequest request = req("GET", "/order/abc/x");

        RouteMatchExplainer.MatchExplanation a =
                RouteMatchExplainer.explain(List.of(broad, specific), request);
        RouteMatchExplainer.MatchExplanation b =
                RouteMatchExplainer.explain(List.of(specific, broad), request);

        assertEquals(a.matchedRouteNo(), b.matchedRouteNo());
        assertEquals(a.matchedRoutes().get(0).routeNo(), b.matchedRoutes().get(0).routeNo());
        assertEquals(a.matchedRoutes().get(1).routeNo(), b.matchedRoutes().get(1).routeNo());
    }

    @Test
    void unmatchedRouteListsEachFailedConditionWithReason() {
        // 路径成立、方法不成立、头缺失：三个条件里两个失败
        GatewayRoute r = route("r", "http://h:1", List.of(
                path("/order/", 1), method("POST", 2), header("X-Caller", "web", 3)));
        RouteMatchExplainer.MatchExplanation e =
                RouteMatchExplainer.explain(List.of(r), req("GET", "/order/abc"));

        assertNull(e.matchedRouteNo());
        assertEquals(1, e.unmatchedRoutes().size());
        RouteMatchExplainer.RouteVerdict v = e.unmatchedRoutes().get(0);
        assertFalse(v.matched());
        assertEquals(0, v.rank());
        assertTrue(v.conditions().get(0).matched(), "路径条件应成立");
        assertFalse(v.conditions().get(1).matched());
        assertTrue(v.conditions().get(1).reason().contains("方法不匹配"));
        assertFalse(v.conditions().get(2).matched());
        assertTrue(v.conditions().get(2).reason().contains("未携带该头"));
        assertTrue(e.summary().contains("404 NO_ROUTE"));
    }

    @Test
    void queryConditionComesFromUrlString_only() {
        GatewayRoute r = route("r", "http://h:1", List.of(query("from", "cart", 1)));

        // 查询串里带参数 → 命中
        assertEquals("r", RouteMatchExplainer.explain(List.of(r), req("GET", "/x?from=cart"))
                .matchedRouteNo());
        // 值/名大小写敏感
        assertNull(RouteMatchExplainer.explain(List.of(r), req("GET", "/x?from=CART"))
                .matchedRouteNo());
        assertNull(RouteMatchExplainer.explain(List.of(r), req("GET", "/x?FROM=cart"))
                .matchedRouteNo());

        // 显式 query map 也被当作「URL 查询串」这一来源
        MatchableRequest withMap = SimpleMatchableRequest.of("GET", "/x", null,
                Map.of("from", List.of("cart")));
        assertEquals("r", RouteMatchExplainer.explain(List.of(r), withMap).matchedRouteNo());
    }

    @Test
    void noCandidateMeansNoRoute() {
        RouteMatchExplainer.MatchExplanation e =
                RouteMatchExplainer.explain(new ArrayList<>(), req("GET", "/order"));
        assertNull(e.matchedRouteNo());
        assertTrue(e.matchedRoutes().isEmpty());
        assertTrue(e.unmatchedRoutes().isEmpty());
        assertTrue(e.summary().contains("命中 0 条"));
    }

    @Test
    void snapshotRevisionIsEchoed() {
        GatewayRoute r = route("r", "http://h:1", List.of(path("/order/", 1)));
        RouteMatchExplainer.MatchExplanation e =
                RouteMatchExplainer.explain(42L, List.of(r), req("GET", "/order/x"));
        assertEquals(42L, e.snapshotRevision());
        assertEquals("r", e.matchedRouteNo());
    }

    @Test
    void methodAndHeaderCaseRulesHoldInExplain() {
        GatewayRoute r = route("r", "http://h:1", List.of(
                method("get", 1), header("X-Caller", "web", 2)));
        MatchableRequest ok = SimpleMatchableRequest.of("get", "/x",
                Map.of("x-caller", "web"), null);
        assertEquals("r", RouteMatchExplainer.explain(List.of(r), ok).matchedRouteNo());

        MatchableRequest wrongHeaderValue = SimpleMatchableRequest.of("GET", "/x",
                Map.of("X-Caller", "WEB"), null);
        assertNull(RouteMatchExplainer.explain(List.of(r), wrongHeaderValue).matchedRouteNo());
    }
}
