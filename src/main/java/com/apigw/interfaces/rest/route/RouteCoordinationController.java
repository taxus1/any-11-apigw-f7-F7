package com.apigw.interfaces.rest.route;

import com.apigw.common.Result;
import com.apigw.proxy.route.RouteCoordinator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 路由多实例版本协调与手动排障接口。
 *
 * <p>生产环境应放在管理端口或加管理员鉴权；强制激活会记录原因，只用于问题实例已被摘流的应急场景。
 */
@RestController
@RequestMapping("/api/gateway/route-coordination")
public class RouteCoordinationController {

    private final RouteCoordinator coordinator;

    public RouteCoordinationController(RouteCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping
    public Mono<Result<RouteCoordinator.CoordinationStatus>> status() {
        return coordinator.status().map(Result::ok);
    }

    @PostMapping("/refresh")
    public Mono<Result<RouteCoordinator.CoordinationStatus>> refresh() {
        return coordinator.refresh().map(Result::ok);
    }

    @PostMapping("/force-activate")
    public Mono<Result<Boolean>> forceActivate(@RequestBody ForceActivateRequest request) {
        if (!"I_UNDERSTAND_SPLIT_BRAIN_RISK".equals(request.acknowledge())) {
            return Mono.just(Result.fail("强制激活必须显式 acknowledge=I_UNDERSTAND_SPLIT_BRAIN_RISK"));
        }
        return coordinator.forceActivate(request.revision(), request.reason())
                .map(ok -> Boolean.TRUE.equals(ok)
                        ? Result.ok(true)
                        : Result.fail("强制激活未完成：预期实例未全部准备/进入栅栏"));
    }

    public record ForceActivateRequest(long revision, String acknowledge, String reason) {
    }
}
