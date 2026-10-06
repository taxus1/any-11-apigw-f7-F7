package com.apigw.proxy.match;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.proxy.match.RouteMatchExplanation.RouteVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匹配解释器测试：解释结果必须与 {@link RouteMatcher} 的判定一致，
 * 且「谁赢、谁被抢、谁没中、为什么」都要说得出。
 */
class RouteMatchExplainerTest {

    private final RouteMatcher matcher = new RouteMatcher();

    private GatewayRoute route(String no, List<GatewayRule> conditions) {
        GatewayRoute r = GatewayRoute.create(no, no, "http://h:1", 1, null);
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

    private RouteVerdict verdictOf(List<RouteVerdict> verdicts, String routeNo) {
        return verdicts.stream().filter(v -> v.routeNo().equals(routeNo)).findFirst().orElseThrow();
    }

    @Test
    void fullHit_isWinner_withPerConditionDetail() {
        GatewayRoute r = route("order", List.of(
                path("/order/", 1), method("GET", 2), header("X-Caller", "web", 3), query("from", "cart", 4)));
        MatchInput input = MatchInput.of("/order/abc", "GET",
                Map.of("X-Caller", "web"), Map.of("from", "cart"));

        List<RouteVerdict> verdicts = RouteMatchExplanation.evaluate(List.of(r), input, matcher);

        RouteVerdict v = verdictOf(verdicts, "order");
        assertTrue(v.matched());
        assertEquals(1, v.rank());
        assertEquals("WINNER", v.outcome());
        assertEquals(4, v.conditions().size());
        assertTrue(v.conditions().stream().allMatch(RouteMatchExplanation.ConditionVerdict::matched));
        // 每条条件都带期望值/实际值/人话原因，排查时不用回查配置
        assertEquals("/order/abc", v.conditions().get(0).actual());
        assertEquals("/order/", v.conditions().get(0).expected());
        assertTrue(v.conditions().stream().allMatch(c -> c.reason() != null && !c.reason().isBlank()));
    }

    @Test
    void oneConditionFails_wholeRouteMisses_andPointsAtTheFailingOne() {
        GatewayRoute r = route("order", List.of(path("/order/", 1), method("POST", 2)));
        MatchInput input = MatchInput.of("/order/abc", "GET", null, null);

        List<RouteVerdict> verdicts = RouteMatchExplanation.evaluate(List.of(r), input, matcher);

        RouteVerdict v = verdictOf(verdicts, "order");
        assertFalse(v.matched());
        assertNull(v.rank());
        assertEquals("CONDITION_FAILED", v.outcome());
        assertTrue(v.note().contains("METHOD"), v.note());
        // 路径那条是过的、方法那条不过，逐条都能对上
        assertTrue(v.conditions().get(0).matched());
        assertFalse(v.conditions().get(1).matched());
        assertEquals("POST", v.conditions().get(1).expected());
        assertEquals("GET", v.conditions().get(1).actual());
    }

    @Test
    void headerAndQuerySemantics_matchRuntimeExactly() {
        GatewayRoute r = route("h", List.of(header("X-Caller", "web", 1), query("from", "cart", 2)));

        // 头名大小写不敏感：配置 X-Caller，请求写 x-caller 也中
        MatchInput lower = MatchInput.of("/x", "GET", Map.of("x-caller", "web"), Map.of("from", "cart"));
        assertTrue(verdictOf(RouteMatchExplanation.evaluate(List.of(r), lower, matcher), "h").matched());

        // 头值大小写敏感：WEB ≠ web
        MatchInput wrongValue = MatchInput.of("/x", "GET", Map.of("X-Caller", "WEB"), Map.of("from", "cart"));
        RouteVerdict v1 = verdictOf(RouteMatchExplanation.evaluate(List.of(r), wrongValue, matcher), "h");
        assertFalse(v1.matched());
        assertTrue(v1.conditions().get(0).reason().contains("期望 web，实际 WEB"));

        // 查询参数名大小写敏感：FROM ≠ from
        MatchInput wrongName = MatchInput.of("/x", "GET", Map.of("X-Caller", "web"), Map.of("FROM", "cart"));
        RouteVerdict v2 = verdictOf(RouteMatchExplanation.evaluate(List.of(r), wrongName, matcher), "h");
        assertFalse(v2.matched());
        assertTrue(v2.conditions().get(1).reason().contains("没有 from 参数"), v2.conditions().get(1).reason());
    }

    @Test
    void lostPrecedence_namesTheWinnerAndWhy() {
        GatewayRoute broad = route("broad", List.of(path("/order/", 1)));
        GatewayRoute specific = route("specific", List.of(path("/order/abc/", 1)));
        MatchInput input = MatchInput.of("/order/abc/x", "GET", null, null);

        List<RouteVerdict> verdicts = RouteMatchExplanation.evaluate(List.of(broad, specific), input, matcher);

        RouteVerdict winner = verdictOf(verdicts, "specific");
        assertEquals(1, winner.rank());
        assertEquals("WINNER", winner.outcome());

        RouteVerdict loser = verdictOf(verdicts, "broad");
        assertTrue(loser.matched(), "全中但输了，与「条件不满足」是两回事");
        assertEquals(2, loser.rank());
        assertEquals("LOST_PRECEDENCE", loser.outcome());
        assertTrue(loser.note().contains("specific"), loser.note());
        assertTrue(loser.note().contains("前缀"), loser.note());
    }

    @Test
    void tiesBreakByConditionCountThenRouteNo_andExplainSaysSo() {
        // 同前缀：条件多的赢
        GatewayRoute simple = route("simple", List.of(path("/order/", 1)));
        GatewayRoute constrained = route("constrained", List.of(path("/order/", 1), method("GET", 2)));
        MatchInput input = MatchInput.of("/order/x", "GET", null, null);

        List<RouteVerdict> verdicts = RouteMatchExplanation.evaluate(List.of(simple, constrained), input, matcher);
        assertEquals(1, verdictOf(verdicts, "constrained").rank());
        assertTrue(verdictOf(verdicts, "simple").note().contains("条件数"),
                verdictOf(verdicts, "simple").note());

        // 完全等价：编号字典序
        GatewayRoute b = route("route-b", List.of(path("/x/", 1)));
        GatewayRoute a = route("route-a", List.of(path("/x/", 1)));
        MatchInput in2 = MatchInput.of("/x/y", "GET", null, null);
        List<RouteVerdict> v2 = RouteMatchExplanation.evaluate(List.of(b, a), in2, matcher);
        assertEquals(1, verdictOf(v2, "route-a").rank());
        assertTrue(verdictOf(v2, "route-b").note().contains("字典序"), verdictOf(v2, "route-b").note());
    }

    @Test
    void noHitAtAll_noWinner() {
        GatewayRoute r = route("order", List.of(path("/order/", 1)));
        MatchInput input = MatchInput.of("/other", "GET", null, null);

        List<RouteVerdict> verdicts = RouteMatchExplanation.evaluate(List.of(r), input, matcher);
        assertTrue(verdicts.stream().noneMatch(v -> v.rank() != null && v.rank() == 1));
    }

    @Test
    void evaluationAgreesWithMatcherVerdict() {
        // 同一批路由 + 同一个输入：解释器说的赢家必须就是 matcher 算的那条
        GatewayRoute a = route("a", List.of(path("/order/", 1)));
        GatewayRoute b = route("b", List.of(path("/order/", 1), method("GET", 2)));
        GatewayRoute c = route("c", List.of(path("/order/abc/", 1)));
        MatchInput input = MatchInput.of("/order/abc/x", "GET", null, null);
        List<GatewayRoute> routes = List.of(a, b, c);

        GatewayRoute matched = matcher.match(routes, input);
        List<RouteVerdict> verdicts = RouteMatchExplanation.evaluate(routes, input, matcher);

        assertEquals(matched.getRouteNo(),
                verdicts.stream().filter(v -> v.rank() != null && v.rank() == 1).findFirst().orElseThrow().routeNo());
    }
}
