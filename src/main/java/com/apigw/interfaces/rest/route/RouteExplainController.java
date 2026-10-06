package com.apigw.interfaces.rest.route;

import com.apigw.application.route.RouteExplainService;
import com.apigw.common.Result;
import com.apigw.interfaces.rest.route.vo.RouteExplainVO;
import com.apigw.proxy.match.RouteMatchExplanation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 路由匹配排查接口：给一条请求描述，算出它最终落到哪条路由、为什么。
 *
 * 只读、不改任何配置；评估用的是转发链路此刻的生效快照，
 * 线上出现「这条请求怎么走到那条路由去了」时，直接拿同样的
 * 路径/方法/头/查询参数调这里核对，不用猜。
 */
@RestController
@RequestMapping("/api/gateway/routes")
public class RouteExplainController {

    private final RouteExplainService explainService;

    public RouteExplainController(RouteExplainService explainService) {
        this.explainService = explainService;
    }

    /**
     * 解释一条请求的匹配过程与最终落点。
     * 任何请求描述都回 code=0 + 完整解释（没命中也是正常结论，对应线上 404 NO_ROUTE）；
     * 只有描述本身不像样（路径为空、方法为空）才回业务失败。
     */
    @PostMapping("/_explain")
    public Mono<Result<RouteMatchExplanation.Explanation>> explain(@RequestBody RouteExplainVO body) {
        return explainService.explain(body.path(), body.method(), body.headers(), body.query())
                .map(Result::ok);
    }
}
