package com.apigw.interfaces.rest.route;

import com.apigw.application.route.GatewayRouteAppService;
import com.apigw.application.route.RouteMatchDebugService;
import com.apigw.common.exception.BizException;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import com.apigw.infrastructure.store.RouteStore;
import com.apigw.common.exception.GlobalExceptionHandler;
import com.apigw.proxy.route.RouteSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理接口 Web 切片测试：Controller + AppService + 领域聚合全部真实，
 * 只把 {@link RouteStore} mock 掉，所以不依赖 Redis 也能验证：
 * - HTTP 协议适配、统一返回结构、全局异常收口；
 * - 请求体进入时的聚合校验（顺序号、类型、上游地址等都在碰 Redis 之前发生）；
 * - 分页数字与每行子项计数的真实计算。
 *
 * 真正连 Redis 的原子占位/乐观锁/级联删见 RouteStoreTest 与 GatewayRouteControllerIT。
 */
class GatewayRouteControllerWebTest {

    private RouteStore store;
    private WebTestClient web;

    @BeforeEach
    void setUp() {
        store = mock(RouteStore.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var appService = new GatewayRouteAppService(store, events);
        web = WebTestClient.bindToController(new GatewayRouteController(appService))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Map<String, Object> rule(String type, String name, String value, int sortNo) {
        var m = new java.util.HashMap<String, Object>();
        m.put("type", type);
        if (name != null) {
            m.put("name", name);
        }
        if (value != null) {
            m.put("value", value);
        }
        m.put("sortNo", sortNo);
        return m;
    }

    private Map<String, Object> body(String routeNo, String name, String upstream,
                                     Integer version,
                                     List<Map<String, Object>> conditions,
                                     List<Map<String, Object>> actions) {
        var m = new java.util.HashMap<String, Object>();
        m.put("routeNo", routeNo);
        m.put("name", name);
        m.put("upstream", upstream);
        m.put("enabled", 1);
        if (version != null) {
            m.put("version", version);
        }
        m.put("conditions", conditions);
        m.put("actions", actions);
        return m;
    }

    @Test
    void create_valid_returnsDetailWithVersionZero() {
        when(store.create(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        web.post().uri("/api/gateway/routes")
                .bodyValue(body("order-01", "订单", "http://order-svc:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/order/", 1),
                                rule("METHOD", null, "GET", 2)),
                        List.of(rule("REQ_ADD_HEADER", "X-Gw", "1", 1))))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.routeNo").isEqualTo("order-01")
                .jsonPath("$.data.version").isEqualTo(0)
                .jsonPath("$.data.conditions.length()").isEqualTo(2)
                .jsonPath("$.data.actions[0].stage").isEqualTo("REQUEST");
    }

    @Test
    void create_duplicateSortNo_rejectedBeforeTouchingStore() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(body("sort-01", "n", "http://h:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1),
                                rule("METHOD", null, "GET", 1)),
                        List.of()))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg")
                .isEqualTo("匹配条件第 1 条与第 2 条的顺序号撞了，都是 1，同组内顺序号不能重");
    }

    @Test
    void create_gapSortNo_rejected() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(body("sort-02", "n", "http://h:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1),
                                rule("METHOD", null, "GET", 3)),
                        List.of()))
                .exchange().expectBody()
                .jsonPath("$.msg")
                .isEqualTo("匹配条件的顺序号必须从 1 起连续不跳号，缺了 2（现在有 2 条）");
    }

    @Test
    void create_unknownActionType_rejectedWithPosition() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(body("t-01", "n", "http://h:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)),
                        List.of(rule("REWRITE_BODY", "X-K", "v", 1))))
                .exchange().expectBody()
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("转发动作第 1 条的类型不支持：REWRITE_BODY"));
    }

    @Test
    void create_badUpstream_rejected() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(body("u-01", "n", "http://###", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of()))
                .exchange().expectBody()
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .containsAnyOf("不是合法的 URL", "主机:端口不合法"));
    }

    @Test
    void create_withAuthRequired_roundTrips_andDefaultsToOpen() {
        when(store.create(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        // 显式打开登录开关：详情回 1
        var withAuth = body("sec-01", "n", "http://h:8080", null,
                List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of());
        withAuth.put("authRequired", 1);
        web.post().uri("/api/gateway/routes").bodyValue(withAuth)
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.authRequired").isEqualTo(1);

        // 不传：默认开放（0），且列表行也带这个字段
        web.post().uri("/api/gateway/routes")
                .bodyValue(body("open-01", "n", "http://h:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/b/", 1)), List.of()))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.authRequired").isEqualTo(0);
    }

    @Test
    void create_illegalAuthRequired_rejected() {
        var illegal = body("sec-02", "n", "http://h:8080", null,
                List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of());
        illegal.put("authRequired", 2);
        web.post().uri("/api/gateway/routes").bodyValue(illegal)
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("登录开关只能是 0（开放）或 1（需登录）"));
    }

    @Test
    void update_routeNoMismatch_rejected() {
        web.put().uri("/api/gateway/routes/order-01")
                .bodyValue(body("order-02", "n", "http://h:8080", 0,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of()))
                .exchange().expectBody()
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .startsWith("路由编号建后不可修改"));
    }

    @Test
    void update_staleVersion_returns409() {
        when(store.update(any())).thenReturn(Mono.error(
                new BizException(409, "你这份配置已经旧了（当前版本 1，你手上是 0），请重新拉取后再提交")));

        web.put().uri("/api/gateway/routes/order-01")
                .bodyValue(body("order-01", "n", "http://h:8080", 0,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of()))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(409)
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("你这份配置已经旧了"));
    }

    @Test
    void update_withoutVersion_rejectedWithClearMessage() {
        // 真实 RouteStore 的版本检查发生在碰 Redis 之前，null redis 也能验证到这条
        var realStore = new RouteStore(null, new ObjectMapper());
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var client = WebTestClient.bindToController(
                        new GatewayRouteController(new GatewayRouteAppService(realStore, events)))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();

        client.put().uri("/api/gateway/routes/order-01")
                .bodyValue(body("order-01", "n", "http://h:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of()))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("必须带上读取时拿到的版本号"));
    }

    @Test
    void detail_missing_returns404() {
        when(store.findByRouteNo("ghost")).thenReturn(Mono.empty());
        web.get().uri("/api/gateway/routes/ghost").exchange().expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.msg").isEqualTo("路由不存在：ghost");
    }

    @Test
    void delete_missing_returns404_notSilentSuccess() {
        when(store.delete(any(), any())).thenReturn(Mono.error(
                new BizException(404, "路由不存在，删除未执行：ghost")));
        web.delete().uri("/api/gateway/routes/ghost").exchange().expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("删除未执行"));
    }

    @Test
    void page_realPagingMath_capSize_keywordAndCounts() {
        List<GatewayRoute> all = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String no = "p-%02d".formatted(i);
            GatewayRoute r = GatewayRoute.create(no, "分页路由" + i, "http://h:8080", 1, null);
            var conds = new ArrayList<GatewayRule>();
            conds.add(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/p" + i + "/", 1));
            if (i % 2 == 0) {
                conds.add(GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "GET", 2));
            }
            r.replaceRules(conds,
                    List.of(GatewayRule.create(null, RuleTypes.TYPE_REQ_REMOVE_HEADER, "X-A", null, 1)));
            all.add(r);
        }
        when(store.findAll()).thenReturn(Flux.fromIterable(all));

        // pageSize 超出上限：真实 AppService 把它压到 200
        web.get().uri("/api/gateway/routes?pageNum=1&pageSize=99999").exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.pageNum").isEqualTo(1)
                .jsonPath("$.data.pageSize").isEqualTo(200)
                .jsonPath("$.data.total").isEqualTo(6)
                .jsonPath("$.data.totalPages").isEqualTo(1)
                .jsonPath("$.data.content.length()").isEqualTo(6)
                .jsonPath("$.data.content[0].conditionCount").isEqualTo(1)
                .jsonPath("$.data.content[0].actionCount").isEqualTo(1);

        // 每页 5 条：第一页 5 条、共 2 页；偶数编号的条件数是 2
        web.get().uri("/api/gateway/routes?pageNum=1&pageSize=5").exchange().expectBody()
                .jsonPath("$.data.total").isEqualTo(6)
                .jsonPath("$.data.totalPages").isEqualTo(2)
                .jsonPath("$.data.content.length()").isEqualTo(5);
        web.get().uri("/api/gateway/routes?pageNum=2&pageSize=5").exchange().expectBody()
                .jsonPath("$.data.content.length()").isEqualTo(1);

        // 编号 / 名称模糊找
        web.get().uri("/api/gateway/routes?keyword=p-02").exchange().expectBody()
                .jsonPath("$.data.total").isEqualTo(1);
        web.get().uri("/api/gateway/routes?keyword=分页").exchange().expectBody()
                .jsonPath("$.data.total").isEqualTo(6);
    }

    // ---- 灰度分组配置在入域时校验（碰 Redis 之前就拦住） ----

    private Map<String, Object> group(String name, String upstream, Object weight,
                                      List<String> tags) {
        var m = new java.util.HashMap<String, Object>();
        m.put("groupName", name);
        m.put("upstream", upstream);
        m.put("weight", weight);
        if (tags != null) {
            m.put("tags", tags);
        }
        return m;
    }

    private Map<String, Object> bodyWithGray(String routeNo, List<Map<String, Object>> groups) {
        var m = body(routeNo, "灰度路由", "http://stable:8080", null,
                List.of(rule("PATH_PREFIX", null, "/g/", 1)), List.of());
        m.put("grayGroups", groups);
        return m;
    }

    @Test
    void create_validGrayGroups_returnsThemInDetail() {
        when(store.create(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        web.post().uri("/api/gateway/routes")
                .bodyValue(bodyWithGray("gray-01", List.of(
                        group("stable", "http://stable:8080", 90, List.of()),
                        group("canary", "http://canary:8080", 10, List.of("v2")))))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.grayGroups.length()").isEqualTo(2)
                .jsonPath("$.data.grayGroups[1].groupName").isEqualTo("canary")
                .jsonPath("$.data.grayGroups[1].weight").isEqualTo(10)
                .jsonPath("$.data.grayGroups[1].tags[0]").isEqualTo("v2");
    }

    @Test
    void create_zeroWeightIsAllowed_configStays() {
        when(store.create(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        web.post().uri("/api/gateway/routes")
                .bodyValue(bodyWithGray("gray-02", List.of(
                        group("stable", "http://stable:8080", 100, null),
                        group("canary", "http://canary:8080", 0, List.of("v2")))))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.grayGroups[1].weight").isEqualTo(0);
    }

    @Test
    void create_weightsNotSumming100_rejectedWithBreakdown() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(bodyWithGray("gray-03", List.of(
                        group("stable", "http://stable:8080", 90, null),
                        group("canary", "http://canary:8080", 5, List.of("v2")))))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString())
                                .contains("权重之和必须恰好为 100")
                                .contains("stable=90").contains("canary=5"));
    }

    @Test
    void create_negativeWeight_rejected() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(bodyWithGray("gray-04", List.of(
                        group("stable", "http://stable:8080", 110, null),
                        group("canary", "http://canary:8080", -10, null))))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString())
                                .contains("权重超出范围"));
    }

    @Test
    void create_weightNotANumber_rejectedAsBadRequestShape() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(bodyWithGray("gray-05", List.of(
                        group("stable", "http://stable:8080", "一成", null),
                        group("canary", "http://canary:8080", 90, null))))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString())
                                .contains("权重必须是整数"));
    }

    @Test
    void create_duplicateTagAcrossGroups_rejected() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(bodyWithGray("gray-06", List.of(
                        group("a", "http://a:8080", 50, List.of("v2")),
                        group("b", "http://b:8080", 50, List.of("v2")))))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString())
                                .contains("灰度标记值 v2 同时挂在分组 a 与分组 b"));
    }

    @Test
    void create_garbageGroupUpstream_rejected() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(bodyWithGray("gray-07", List.of(
                        group("stable", "http://stable:8080", 90, null),
                        group("canary", "not-a-url", 10, null))))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString())
                                .contains("灰度分组第 2 条的上游地址"));
    }

    // ---- 韧性（熔断/重试）配置进接口 ----

    private Map<String, Object> resilienceBody(String routeNo, Map<String, Object> resilience) {
        var m = body(routeNo, "n", "http://h:8080", null,
                List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of());
        if (resilience != null) {
            m.put("resilience", resilience);
        }
        return m;
    }

    @Test
    void create_withResilience_persistsAndEchoesBothPolicies() {
        when(store.create(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var cb = Map.<String, Object>of(
                "windowSize", 30,
                "minimumNumberOfCalls", 8,
                "failureRateThreshold", 60,
                "minFailureCount", 6,
                "openWaitMs", 20000,
                "trialFraction", 25,
                "successThreshold", 2);
        var rt = Map.<String, Object>of(
                "maxAttempts", 3,
                "backoffMs", 100,
                "totalTimeoutMs", 9000,
                "idempotentMethods", List.of("PUT", "DELETE"),
                "idempotencyKeyHeader", "Idempotency-Key");
        var resilience = Map.<String, Object>of(
                "circuitBreakerEnabled", 1, "circuitBreaker", cb,
                "retryEnabled", 1, "retry", rt);

        web.post().uri("/api/gateway/routes")
                .bodyValue(resilienceBody("res-01", resilience))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.resilience.circuitBreakerEnabled").isEqualTo(1)
                .jsonPath("$.data.resilience.circuitBreaker.windowSize").isEqualTo(30)
                .jsonPath("$.data.resilience.circuitBreaker.trialFraction").isEqualTo(25)
                .jsonPath("$.data.resilience.retryEnabled").isEqualTo(1)
                .jsonPath("$.data.resilience.retry.maxAttempts").isEqualTo(3)
                .jsonPath("$.data.resilience.retry.idempotencyKeyHeader").isEqualTo("Idempotency-Key");
    }

    @Test
    void create_resilienceDisabledByDefault_hasNoResilienceBlock() {
        when(store.create(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        web.post().uri("/api/gateway/routes")
                .bodyValue(resilienceBody("res-02", null))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.resilience").doesNotExist();
    }

    @Test
    void create_badCircuitBreakerThreshold_rejected() {
        var cb = Map.<String, Object>of("failureRateThreshold", 0);
        var resilience = Map.<String, Object>of(
                "circuitBreakerEnabled", 1, "circuitBreaker", cb,
                "retryEnabled", 0);
        web.post().uri("/api/gateway/routes")
                .bodyValue(resilienceBody("res-03", resilience))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString())
                                .contains("failureRateThreshold"));
    }

    @Test
    void create_retryAttemptsTooLarge_rejected() {
        var rt = Map.<String, Object>of("maxAttempts", 99);
        var resilience = Map.<String, Object>of(
                "circuitBreakerEnabled", 0,
                "retryEnabled", 1, "retry", rt);
        web.post().uri("/api/gateway/routes")
                .bodyValue(resilienceBody("res-04", resilience))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString())
                                .contains("maxAttempts"));
    }

    // ---- 落点排查接口 ----

    /** 单独搭一个挂了 debug 用例（RouteCatalog 由 mock 提供快照）的客户端。 */
    private WebTestClient debugClient(List<GatewayRoute> snapshotRoutes, long revision) {
        var catalog = mock(com.apigw.proxy.route.RouteCatalog.class);
        when(catalog.snapshot()).thenReturn(Mono.just(
                new RouteSnapshot(revision, "ck", snapshotRoutes, Instant.now(), Instant.now())));
        var debugService = new RouteMatchDebugService(catalog);
        var app = new GatewayRouteAppService(store,
                mock(org.springframework.context.ApplicationEventPublisher.class));
        return WebTestClient.bindToController(new GatewayRouteController(app, debugService))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private GatewayRoute matchingRoute(String no, String upstream,
                                       List<GatewayRule> conditions) {
        GatewayRoute r = GatewayRoute.create(no, no, upstream, 1, null);
        r.replaceRules(conditions, List.of());
        return r;
    }

    @Test
    void debugMatch_reportsWinnerRankAndPerConditionReasons() {
        GatewayRoute broad = matchingRoute("broad", "http://h:1",
                List.of(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/order/", 1)));
        GatewayRoute specific = matchingRoute("specific", "http://h:2",
                List.of(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/order/abc/", 1),
                        GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "GET", 2)));
        GatewayRoute other = matchingRoute("other", "http://h:3",
                List.of(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/pay/", 1)));
        WebTestClient client = debugClient(List.of(broad, specific, other), 7L);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("method", "get"); // 小写方法也要认
        body.put("path", "/order/abc/x");
        client.post().uri("/api/gateway/routes/debug/match").bodyValue(body)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.matchedRouteNo").isEqualTo("specific")
                .jsonPath("$.data.snapshotRevision").isEqualTo(7)
                // 命中两条：specific 第 1、broad 第 2（被抢）
                .jsonPath("$.data.matchedRoutes.length()").isEqualTo(2)
                .jsonPath("$.data.matchedRoutes[0].routeNo").isEqualTo("specific")
                .jsonPath("$.data.matchedRoutes[0].rank").isEqualTo(1)
                .jsonPath("$.data.matchedRoutes[1].routeNo").isEqualTo("broad")
                .jsonPath("$.data.matchedRoutes[1].rank").isEqualTo(2)
                .jsonPath("$.data.matchedRoutes[1].reason").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString())
                                .contains("被更靠前的路由抢走").contains("specific"))
                // /pay 那条不命中，且给出路径不匹配原因
                .jsonPath("$.data.unmatchedRoutes.length()").isEqualTo(1)
                .jsonPath("$.data.unmatchedRoutes[0].routeNo").isEqualTo("other")
                .jsonPath("$.data.unmatchedRoutes[0].conditions[0].matched").isEqualTo(false)
                .jsonPath("$.data.summary").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString()).contains("specific"));
    }

    @Test
    void debugMatch_acceptsHeaderAndQueryFromPath() {
        GatewayRoute r = matchingRoute("r", "http://h:1",
                List.of(GatewayRule.create(null, RuleTypes.TYPE_HEADER, "X-Caller", "web", 1),
                        GatewayRule.create(null, RuleTypes.TYPE_QUERY, "from", "cart", 2)));
        WebTestClient client = debugClient(List.of(r), 1L);

        // 查询串直接带在 path 里；头名小写
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("method", "GET");
        body.put("path", "/order/x?from=cart");
        body.put("headers", Map.of("x-caller", "web"));
        client.post().uri("/api/gateway/routes/debug/match").bodyValue(body)
                .exchange().expectBody()
                .jsonPath("$.data.matchedRouteNo").isEqualTo("r");

        // 查询值不对 → 不命中，QUERY 条件给出原因
        Map<String, Object> bad = new java.util.HashMap<>();
        bad.put("method", "GET");
        bad.put("path", "/order/x?from=buy");
        bad.put("headers", Map.of("x-caller", "web"));
        client.post().uri("/api/gateway/routes/debug/match").bodyValue(bad)
                .exchange().expectBody()
                .jsonPath("$.data.matchedRouteNo").doesNotExist()
                .jsonPath("$.data.unmatchedRoutes[0].conditions[1].reason")
                .value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("查询参数不匹配"));
    }

    @Test
    void debugMatch_queryAcceptsArrayValue() {
        GatewayRoute r = matchingRoute("r", "http://h:1",
                List.of(GatewayRule.create(null, RuleTypes.TYPE_QUERY, "id", "2", 1)));
        WebTestClient client = debugClient(List.of(r), 1L);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("method", "GET");
        body.put("path", "/x");
        body.put("query", Map.of("id", List.of("1", "2", "3"))); // 数组形态多值
        client.post().uri("/api/gateway/routes/debug/match").bodyValue(body)
                .exchange().expectBody()
                .jsonPath("$.data.matchedRouteNo").isEqualTo("r");
    }

    @Test
    void debugMatch_missingMethod_isRejectedClearly() {
        WebTestClient client = debugClient(List.of(), 1L);
        client.post().uri("/api/gateway/routes/debug/match")
                .bodyValue(Map.of("path", "/x"))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v ->
                        org.assertj.core.api.Assertions.assertThat(v.toString()).contains("method"));
    }
}
