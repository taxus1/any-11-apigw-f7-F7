package com.apigw.proxy.route;

import com.apigw.domain.route.GatewayRoute;

import java.time.Instant;
import java.util.List;

/**
 * 转发侧正在使用的一整份不可变路由配置。
 *
 * <p>{@code revision} 是全量路由集合的版本；同一次请求一旦拿到该对象，匹配条件、动作、灰度组
 * 都固定使用这一份，后台切换不会影响在途请求。
 */
public record RouteSnapshot(
        long revision,
        String checksum,
        List<GatewayRoute> routes,
        Instant committedAt,
        Instant loadedAt) {

    public RouteSnapshot {
        routes = List.copyOf(routes);
    }
}
