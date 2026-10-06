package com.apigw.infrastructure.store;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GrayGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由 JSON 序列化/反序列化测试（不碰 Redis：serialize/deserialize 是纯映射）。
 * 重点：登录开关 authRequired 跟路由配置一起存取；旧配置 JSON 没这个字段时按开放（0）落。
 * 灰度：grayGroups 随路由整体存取；旧配置 JSON 没这个字段时按「无灰度」落，全量回主上游。
 */
class RouteStoreSerializationTest {

    private final RouteStore store = new RouteStore(null, new ObjectMapper());

    @Test
    void authRequired_roundTripsThroughJson() {
        GatewayRoute r = GatewayRoute.create("secure-01", "需登录路由", "http://svc:8080", 1, null);
        r.changeAuthRequired(1);
        String json = store.serialize(r);
        assertThat(json).contains("\"authRequired\":1");

        GatewayRoute back = store.deserialize(json);
        assertThat(back.getAuthRequired()).isEqualTo(1);
        assertThat(back.requiresAuth()).isTrue();
    }

    @Test
    void legacyJsonWithoutAuthRequired_defaultsToOpen() {
        // 开关上线前写进 Redis 的旧配置：字段缺失 → 默认开放，不能突然变「需登录」
        String legacy = "{\"id\":\"id-1\",\"routeNo\":\"legacy\",\"name\":\"旧路由\","
                + "\"upstream\":\"http://svc:8080\",\"enabled\":1,\"version\":0,"
                + "\"conditions\":[],\"actions\":[]}";
        GatewayRoute back = store.deserialize(legacy);
        assertThat(back.getAuthRequired()).isEqualTo(0);
        assertThat(back.requiresAuth()).isFalse();
    }

    @Test
    void grayGroups_roundTripThroughJson() {
        GatewayRoute r = GatewayRoute.create("gray-01", "灰度路由", "http://stable:8080", 1, null);
        r.setVersion(2);
        r.replaceGrayGroups(List.of(
                GrayGroup.create("stable", "http://stable:8080", 90, List.of()),
                GrayGroup.create("canary", "http://canary:8080", 10, List.of("v2", "beta"))));

        String json = store.serialize(r);
        assertThat(json).contains("\"grayGroups\"").contains("canary").contains("\"weight\":10");

        GatewayRoute back = store.deserialize(json);
        assertThat(back.hasGrayGroups()).isTrue();
        assertThat(back.getGrayGroups()).hasSize(2);
        assertThat(back.getGrayGroups().get(1).getTags()).containsExactly("v2", "beta");
        assertThat(back.getVersion()).isEqualTo(2);
    }

    @Test
    void legacyJsonWithoutGrayGroups_defaultsToNoGray() {
        // 灰度上线前写进 Redis 的旧配置：grayGroups 字段缺失 → 无灰度，全量打主上游
        String legacy = "{\"id\":\"id-1\",\"routeNo\":\"legacy\",\"name\":\"旧路由\","
                + "\"upstream\":\"http://svc:8080\",\"enabled\":1,\"authRequired\":0,\"version\":3,"
                + "\"conditions\":[],\"actions\":[]}";
        GatewayRoute back = store.deserialize(legacy);
        assertThat(back.hasGrayGroups()).isFalse();
        assertThat(back.getGrayGroups()).isEmpty();
    }

    @Test
    void resilience_roundTripsThroughJson() {
        GatewayRoute r = GatewayRoute.create("res-01", "韧性路由", "http://svc:8080", 1, null);
        var policy = new com.apigw.domain.route.ResiliencePolicy();
        policy.setCircuitBreakerEnabled(1);
        var cb = new com.apigw.domain.route.CircuitBreakerPolicy();
        cb.setWindowSize(30);
        cb.setMinFailureCount(8);
        cb.setFailureRateThreshold(60);
        cb.setMinimumNumberOfCalls(10);
        cb.setOpenWaitMs(5_000L);
        cb.setTrialFraction(20);
        cb.setSuccessThreshold(2);
        policy.setCircuitBreaker(cb);
        policy.setRetryEnabled(1);
        var rt = new com.apigw.domain.route.RetryPolicy();
        rt.setMaxAttempts(3);
        rt.setBackoffMs(150L);
        rt.setTotalTimeoutMs(8_000L);
        rt.setIdempotentMethods(java.util.List.of("PUT", "DELETE"));
        rt.setIdempotencyKeyHeader("Idempotency-Key");
        policy.setRetry(rt);
        r.replaceResilience(policy);

        String json = store.serialize(r);
        assertThat(json).contains("\"resilience\"").contains("\"circuitBreakerEnabled\":1")
                .contains("\"retryEnabled\":1").contains("Idempotency-Key");

        GatewayRoute back = store.deserialize(json);
        assertThat(back.circuitBreakerPolicy()).isNotNull();
        assertThat(back.circuitBreakerPolicy().getWindowSize()).isEqualTo(30);
        assertThat(back.circuitBreakerPolicy().getTrialFraction()).isEqualTo(20);
        assertThat(back.retryPolicy()).isNotNull();
        assertThat(back.retryPolicy().getMaxAttempts()).isEqualTo(3);
        assertThat(back.retryPolicy().getIdempotentMethods()).containsExactly("PUT", "DELETE");
        // 带幂等键的 POST 在往返后仍可重试
        assertThat(back.retryPolicy().isRetryable("POST", true)).isTrue();
        assertThat(back.retryPolicy().isRetryable("POST", false)).isFalse();
    }

    @Test
    void legacyJsonWithoutResilience_defaultsToBothOff() {
        String legacy = "{\"id\":\"id-1\",\"routeNo\":\"legacy\",\"name\":\"旧路由\","
                + "\"upstream\":\"http://svc:8080\",\"enabled\":1,\"authRequired\":0,\"version\":3,"
                + "\"conditions\":[],\"actions\":[]}";
        GatewayRoute back = store.deserialize(legacy);
        assertThat(back.circuitBreakerPolicy()).isNull();
        assertThat(back.retryPolicy()).isNull();
    }
}
