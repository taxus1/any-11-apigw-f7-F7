package com.apigw.interfaces.rest.route;

import com.apigw.application.route.GatewayRouteAppService;
import com.apigw.application.route.RouteMatchDebugService;
import com.apigw.common.Result;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.GrayGroup;
import com.apigw.domain.route.CircuitBreakerPolicy;
import com.apigw.domain.route.ResiliencePolicy;
import com.apigw.domain.route.RetryPolicy;
import com.apigw.infrastructure.store.dto.PageResult;
import com.apigw.infrastructure.store.dto.RouteView;
import com.apigw.interfaces.rest.route.vo.RouteDetailVO;
import com.apigw.interfaces.rest.route.vo.RouteMatchDebugRequest;
import com.apigw.interfaces.rest.route.vo.RouteMatchDebugResponse;
import com.apigw.interfaces.rest.route.vo.RouteSaveVO;
import com.apigw.proxy.match.SimpleMatchableRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 路由配置管理接口（用户接口层）。
 *
 * 只做协议适配：解析入参、把 VO 转成领域对象、把领域结果转回 VO；
 * 业务编排交给应用层；不在这里写任何业务规则。
 */
@RestController
@RequestMapping("/api/gateway/routes")
public class GatewayRouteController {

    private final GatewayRouteAppService appService;
    private final RouteMatchDebugService debugService;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayRouteController(GatewayRouteAppService appService,
                                  RouteMatchDebugService debugService) {
        this.appService = appService;
        this.debugService = debugService;
    }

    /** 不需要落点排查的切片测试可只传应用服务。 */
    public GatewayRouteController(GatewayRouteAppService appService) {
        this(appService, null);
    }

    /** 新建路由。 */
    @PostMapping
    public Mono<Result<RouteDetailVO>> create(@RequestBody RouteSaveVO body) {
        GatewayRoute route = toDomain(body);
        return appService.create(route)
                .map(RouteDetailVO::of)
                .map(Result::ok);
    }

    /** 修改路由（编号不可改）。 */
    @PutMapping("/{routeNo}")
    public Mono<Result<RouteDetailVO>> update(@PathVariable String routeNo,
                                              @RequestBody RouteSaveVO body) {
        GatewayRoute route = toDomain(body);
        return appService.update(routeNo, route)
                .map(RouteDetailVO::of)
                .map(Result::ok);
    }

    /** 查一条路由详情（含全部子项）。 */
    @GetMapping("/{routeNo}")
    public Mono<Result<RouteDetailVO>> detail(@PathVariable String routeNo) {
        return appService.detail(routeNo)
                .map(RouteDetailVO::of)
                .map(Result::ok);
    }

    /** 分页列表。 */
    @GetMapping
    public Mono<Result<PageResult<RouteView>>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        return appService.page(pageNum, pageSize, keyword).map(Result::ok);
    }

    /**
     * 落点排查：给一笔请求（方法/路径/头/查询参数），算出它最终命中哪条路由，并逐条解释。
     *
     * 用 POST 是因为要带方法、头和查询参数这份「虚拟请求」，不适合全塞进 GET 的查询串；
     * 它只读取当前生效快照做计算，不改任何配置。结论与真实转发同口径（同一份快照、同一套判定）。
     */
    @PostMapping("/debug/match")
    public Mono<Result<RouteMatchDebugResponse>> debugMatch(@RequestBody RouteMatchDebugRequest body) {
        java.util.Map<String, java.util.List<String>> query = new java.util.LinkedHashMap<>();
        if (body.query() != null) {
            body.query().forEach((k, v) -> query.put(k, RouteMatchDebugRequest.toValueList(v)));
        }
        var request = SimpleMatchableRequest.of(body.method(), body.path(), body.headers(), query);
        return debugService.explain(request)
                .map(RouteMatchDebugResponse::of)
                .map(Result::ok);
    }

    /**
     * 删除路由。
     * expectVersion 可选：带上它就能拦住「别人先改了、我还在删旧版」的情况。
     */
    @DeleteMapping("/{routeNo}")
    public Mono<Result<Void>> delete(@PathVariable String routeNo,
                                     @RequestParam(required = false) Integer expectVersion) {
        return appService.delete(routeNo, expectVersion)
                .thenReturn(Result.ok());
    }

    private GatewayRoute toDomain(RouteSaveVO body) {
        List<GatewayRule> conditions = body.conditions() == null ? List.of()
                : body.conditions().stream().map(GatewayRouteController::toRule).toList();
        List<GatewayRule> actions = body.actions() == null ? List.of()
                : body.actions().stream().map(GatewayRouteController::toRule).toList();
        List<GrayGroup> groups = body.grayGroups() == null ? List.of()
                : body.grayGroups().stream().map(GatewayRouteController::toGrayGroup).toList();
        return appService.assemble(body.routeNo(), body.name(), body.upstream(),
                body.enabled(), body.authRequired(), body.remark(), body.version(),
                conditions, actions, groups, toResilience(body.resilience()));
    }

    private static GatewayRule toRule(RouteSaveVO.RuleVO vo) {
        return GatewayRule.create(vo.stage(), vo.type(), vo.name(), vo.value(), vo.sortNo());
    }

    private static GrayGroup toGrayGroup(RouteSaveVO.GrayGroupVO vo) {
        return GrayGroup.create(vo.groupName(), vo.upstream(), vo.weight(), vo.tags());
    }

    private static ResiliencePolicy toResilience(RouteSaveVO.ResilienceVO vo) {
        if (vo == null) {
            return null;
        }
        ResiliencePolicy p = new ResiliencePolicy();
        p.setCircuitBreakerEnabled(vo.circuitBreakerEnabled());
        p.setRetryEnabled(vo.retryEnabled());
        if (vo.circuitBreaker() != null) {
            var c = vo.circuitBreaker();
            var cb = new CircuitBreakerPolicy();
            cb.setWindowSize(c.windowSize());
            cb.setMinimumNumberOfCalls(c.minimumNumberOfCalls());
            cb.setFailureRateThreshold(c.failureRateThreshold());
            cb.setMinFailureCount(c.minFailureCount());
            cb.setOpenWaitMs(c.openWaitMs());
            cb.setTrialFraction(c.trialFraction());
            cb.setSuccessThreshold(c.successThreshold());
            p.setCircuitBreaker(cb);
        }
        if (vo.retry() != null) {
            var r = vo.retry();
            var rt = new RetryPolicy();
            rt.setMaxAttempts(r.maxAttempts());
            rt.setBackoffMs(r.backoffMs());
            rt.setTotalTimeoutMs(r.totalTimeoutMs());
            rt.setIdempotentMethods(r.idempotentMethods() == null
                    ? new java.util.ArrayList<>() : new java.util.ArrayList<>(r.idempotentMethods()));
            rt.setIdempotencyKeyHeader(r.idempotencyKeyHeader());
            p.setRetry(rt);
        }
        return p;
    }
}
