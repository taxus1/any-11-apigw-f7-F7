package com.apigw.proxy.match;

import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;

import java.util.List;

/**
 * 匹配判定所看的「一笔请求」最小视图。
 *
 * <p>真实转发用 {@link org.springframework.http.server.reactive.ServerHttpRequest} 的适配器
 * （见 {@link ServerHttpRequestView}），排查接口用一份从入参手工构造的实现
 * （见 {@link SimpleMatchableRequest}）。两边走的是 {@link RouteMatcher} 里同一套条件判定，
 * 不会出现「转发是一个结果、排查是另一个结果」。
 *
 * <p>各取值的口径与 HTTP 语义对齐：
 * - method：返回原始方法名，方法大小写不敏感由判定侧统一归一；
 * - path：应用内路径（不含 query），路径按常规 URL 语义大小写敏感；
 * - header：头名按 HTTP 语义大小写不敏感取值，头值比较大小写敏感；
 * - query：只认 URL 查询串上的参数（不是请求体），参数名/值都大小写敏感。
 */
public interface MatchableRequest {

    /** HTTP 方法名（原样，比较时由判定侧归一大小写）；可能为 null（非常规请求）。 */
    String method();

    /** 应用内路径，不含查询串，如 /order/abc。 */
    String path();

    /** 按 HTTP 头名大小写不敏感取第一个值；没有该头返回 null。 */
    String header(String name);

    /** 取 URL 查询串上某参数的全部取值（参数名大小写敏感）；没有该参数返回 null。 */
    List<String> queryParams(String name);
}
