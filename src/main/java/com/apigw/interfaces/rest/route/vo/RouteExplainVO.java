package com.apigw.interfaces.rest.route.vo;

import java.io.Serializable;
import java.util.Map;

/**
 * 「这条请求会落到哪条路由」的排查入参。
 *
 * headers / query 可空；query 认的是 URL 查询串（?from=cart 那一串），
 * 与请求体无关——排查表单里不用填 body。
 */
public record RouteExplainVO(String path,
                             String method,
                             Map<String, String> headers,
                             Map<String, String> query) implements Serializable {
}
