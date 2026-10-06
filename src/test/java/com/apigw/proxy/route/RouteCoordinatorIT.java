package com.apigw.proxy.route;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import com.apigw.infrastructure.store.RouteBarrierStore;
import com.apigw.infrastructure.store.RouteRevisionStore;
import com.apigw.infrastructure.store.RouteStore;
import com.apigw.support.EnabledIfRedis;
import com.apigw.support.RedisAvailableCondition;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.resource.ClientResources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfRedis
class RouteCoordinatorIT {

    private ReactiveRedisConnectionFactory factory;
    private ReactiveStringRedisTemplate redis;
    private RouteStore store;
    private RouteRevisionStore revisions;
    private RouteBarrierStore barriers;
    private RouteCoordinator coordinator;

    @BeforeEach
    void setUp() {
        var clientConfig = LettuceClientConfiguration.builder()
                .clientResources(ClientResources.create())
                .build();
        var connConfig = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                RedisAvailableCondition.host(), RedisAvailableCondition.port());
        var lettuceFactory = new LettuceConnectionFactory(connConfig, clientConfig);
        lettuceFactory.afterPropertiesSet();
        factory = lettuceFactory;
        redis = new ReactiveStringRedisTemplate(lettuceFactory);
        ObjectMapper objectMapper = new ObjectMapper();
        revisions = new RouteRevisionStore(redis);
        barriers = new RouteBarrierStore(redis, objectMapper);
        store = new RouteStore(redis, objectMapper, revisions);
        cleanupKeys().block();
        RouteCoordinationProperties properties = new RouteCoordinationProperties(
                true, 1, "it-gateway", Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(1),
                Duration.ofSeconds(2), 20);
        coordinator = new RouteCoordinator(revisions, barriers, store, redis, objectMapper, properties);
    }

    @AfterEach
    void tearDown() {
        cleanupKeys().block();
        ((LettuceConnectionFactory) factory).destroy();
    }

    private reactor.core.publisher.Mono<Void> cleanupKeys() {
        return redis.scan(org.springframework.data.redis.core.ScanOptions
                        .scanOptions().match("apigw:route*").build())
                .collectList()
                .flatMap(keys -> keys.isEmpty() ? reactor.core.publisher.Mono.empty()
                        : redis.delete(reactor.core.publisher.Flux.fromIterable(keys)).then());
    }

    private GatewayRoute route(String no, String path, Integer version) {
        GatewayRoute r = GatewayRoute.create(no, no, "http://order-svc:8080", 1, null);
        r.setVersion(version);
        r.replaceRules(
                List.of(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, path, 1)),
                List.of());
        return r;
    }

    @Test
    void singleInstanceBootstrapsAndActivatesNewRevision() {
        store.create(route("coord-a", "/old/", 99)).block();
        coordinator.start();
        waitUntil(() -> coordinator.currentSnapshot() != null
                && coordinator.currentSnapshot().revision() == 1);

        GatewayRoute changed = route("coord-a", "/new/", 0);
        store.update(changed).block();
        waitUntil(() -> coordinator.currentSnapshot() != null
                && coordinator.currentSnapshot().revision() == 2);

        RouteSnapshot snapshot = coordinator.snapshot().block();
        assertEquals(2, snapshot.revision());
        assertEquals("/new/", snapshot.routes().get(0).getConditions().get(0).getValue());
        RouteCoordinator.CoordinationStatus status = coordinator.status().block();
        assertEquals(2L, status.activeRevision());
        assertEquals(1, status.instances().size());
        assertEquals("ACTIVE", status.instances().get(0).state());
    }

    @Test
    void threeInstances_prepareTogetherAndActivateSameRevision() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<RouteCoordinator> coordinators = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String instanceId = "cluster-gw-" + i;
            RouteCoordinationProperties props = new RouteCoordinationProperties(
                    true, 3, instanceId, Duration.ofMillis(100), Duration.ofMillis(100),
                    Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(2),
                    Duration.ofSeconds(2), 20);
            RouteCoordinator clusterCoordinator = new RouteCoordinator(
                    revisions, barriers, store, redis, objectMapper, props);
            coordinators.add(clusterCoordinator);
            clusterCoordinator.start();
        }

        store.create(route("coord-c", "/old/", 99)).block();
        waitUntil(() -> coordinators.stream().allMatch(c -> c.currentSnapshot() != null
                && c.currentSnapshot().revision() == 1));

        store.update(route("coord-c", "/new/", 0)).block();
        waitUntil(() -> coordinators.stream().allMatch(c -> c.currentSnapshot() != null
                && c.currentSnapshot().revision() == 2));

        for (RouteCoordinator clusterCoordinator : coordinators) {
            assertEquals(2, clusterCoordinator.currentSnapshot().revision());
            assertEquals("/new/",
                    clusterCoordinator.currentSnapshot().routes().get(0).getConditions().get(0).getValue());
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertTrue(condition.getAsBoolean(), "等待路由协调状态超时");
    }
}
