package com.apigw.proxy.match;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.List;

/**
 * 把转发链路上的 {@link ServerHttpRequest} 适配成 {@link MatchableRequest}。
 *
 * 头名大小写不敏感由 Spring 的 HttpHeaders（底层大小写不敏感 map）天然保证；
 * 查询参数直接取 Spring 已从 URL 查询串解析好的 MultiValueMap，不碰请求体。
 */
public final class ServerHttpRequestView implements MatchableRequest {

    private final ServerHttpRequest request;

    private ServerHttpRequestView(ServerHttpRequest request) {
        this.request = request;
    }

    public static ServerHttpRequestView of(ServerHttpRequest request) {
        return new ServerHttpRequestView(request);
    }

    @Override
    public String method() {
        return request.getMethod() == null ? null : request.getMethod().name();
    }

    @Override
    public String path() {
        return request.getPath().pathWithinApplication().value();
    }

    @Override
    public String header(String name) {
        return request.getHeaders().getFirst(name);
    }

    @Override
    public List<String> queryParams(String name) {
        return request.getQueryParams().get(name);
    }
}
