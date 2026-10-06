package com.apigw.proxy.route;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 路由版本协调参数。
 *
 * @param enabled 是否启用 revision + 多实例两阶段切换；关闭时保留旧的单实例定时刷新行为
 * @param expectedInstances 接流量且必须完成准备的实例数，单机为 1，三台集群为 3
 * @param instanceId 稳定实例 ID；生产建议由 StatefulSet hostname 或显式环境变量注入
 * @param pollInterval Pub/Sub 丢消息时的兜底轮询周期
 * @param heartbeatInterval 实例心跳周期
 * @param heartbeatTtl 心跳过期时间
 * @param prepareTimeout 某版本等待所有实例准备完成的超时
 * @param fenceTimeout 所有实例进入切换栅栏的超时
 * @param gateWaitTimeout 转发请求在切换栅栏前最多等待多久
 * @param snapshotRetention 保留的不可变快照数量
 */
@ConfigurationProperties(prefix = "apigw.proxy.route")
public record RouteCoordinationProperties(
        boolean enabled,
        int expectedInstances,
        String instanceId,
        Duration pollInterval,
        Duration heartbeatInterval,
        Duration heartbeatTtl,
        Duration prepareTimeout,
        Duration fenceTimeout,
        Duration gateWaitTimeout,
        int snapshotRetention) {

    public RouteCoordinationProperties {
        if (expectedInstances <= 0) {
            expectedInstances = 1;
        }
        if (instanceId == null || instanceId.isBlank()) {
            instanceId = System.getenv().getOrDefault("GATEWAY_INSTANCE_ID",
                    System.getenv().getOrDefault("HOSTNAME", "gateway-" + ProcessHandle.current().pid()));
        }
        if (pollInterval == null) {
            pollInterval = Duration.ofSeconds(1);
        }
        if (heartbeatInterval == null) {
            heartbeatInterval = Duration.ofSeconds(1);
        }
        if (heartbeatTtl == null) {
            heartbeatTtl = Duration.ofSeconds(5);
        }
        if (prepareTimeout == null) {
            prepareTimeout = Duration.ofSeconds(3);
        }
        if (fenceTimeout == null) {
            fenceTimeout = Duration.ofMillis(800);
        }
        if (gateWaitTimeout == null) {
            gateWaitTimeout = Duration.ofSeconds(1);
        }
        if (snapshotRetention <= 0) {
            snapshotRetention = 20;
        }
    }
}
