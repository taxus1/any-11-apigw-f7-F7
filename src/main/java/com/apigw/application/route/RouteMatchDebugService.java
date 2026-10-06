package com.apigw.application.route;

import com.apigw.proxy.match.MatchableRequest;
import com.apigw.proxy.match.RouteMatchExplainer;
import com.apigw.proxy.route.RouteCatalog;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 路由落点排查用例：拿「一笔待验证请求」算出它会走哪条路由、为什么。
 *
 * 候选路由直接取 {@link RouteCatalog#snapshot()}——和转发过滤器是同一份「当前启用、可匹配」快照
 * （连 revision 都一起回显），所以排查结论与真实转发口径一致，不是另查一份配置另算一遍。
 */
@Service
public class RouteMatchDebugService {

    private final RouteCatalog routeCatalog;

    public RouteMatchDebugService(RouteCatalog routeCatalog) {
        this.routeCatalog = routeCatalog;
    }

    public Mono<RouteMatchExplainer.MatchExplanation> explain(MatchableRequest request) {
        return routeCatalog.snapshot().map(snapshot ->
                RouteMatchExplainer.explain(snapshot.revision(), snapshot.routes(), request));
    }
}
