package com.apigw.interfaces.rest.route.vo;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 路由落点排查的请求体：描述一笔待验证的请求。
 *
 * - method 必填，大小写不敏感（get 与 GET 等价）；
 * - path 必填，以 / 开头，可直接把查询串带在路径里（/order/abc?from=cart），查询参数只认这里，不读 body；
 * - headers 可空：头名大小写不敏感、头值大小写敏感，同名头取第一个；
 * - query 可空：参数名/值都大小写敏感；值既可以是单个字符串，也可以是字符串数组（同名多值）。
 */
public record RouteMatchDebugRequest(String method,
                                     String path,
                                     Map<String, String> headers,
                                     Map<String, Object> query) implements Serializable {

    /** 把单个值或字符串数组统一归一成字符串列表；其它类型用 toString 收下，不因为入参形态把排查打挂。 */
    public static List<String> toValueList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        return List.of(raw.toString());
    }
}
