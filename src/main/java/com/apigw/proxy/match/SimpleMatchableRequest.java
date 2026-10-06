package com.apigw.proxy.match;

import com.apigw.common.exception.BizException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 排查接口用的「手工构造请求」：从入参的方法/路径/头/查询参数攒出一份 {@link MatchableRequest}。
 *
 * 口径刻意与真实请求一致：
 * - 查询参数认的是 URL 上那一串。优先解析 {@code rawPath} 里自带的 {@code ?a=b}，
 *   再并入显式给的 {@code query}；两者同名时以显式 {@code query} 为准（方便只写路径、单独列参数）。
 *   这里绝不读请求体——排查的就是「URL 查询串」这一来源。
 * - 头名大小写不敏感存储（HTTP 头本来就不分大小写），头值原样保留（比较时大小写敏感）；
 *   同名头只留第一个，和转发侧 {@code getFirst} 口径一致。
 * - 路径取 {@code ?} 之前的部分，按常规 URL 语义大小写敏感，不做解码改写。
 */
public final class SimpleMatchableRequest implements MatchableRequest {

    private final String method;
    private final String path;
    private final TreeMap<String, String> headers;
    private final Map<String, List<String>> query;

    private SimpleMatchableRequest(String method, String path,
                                   TreeMap<String, String> headers,
                                   Map<String, List<String>> query) {
        this.method = method;
        this.path = path;
        this.headers = headers;
        this.query = query;
    }

    /**
     * 组装一笔待排查请求。
     *
     * @param method  HTTP 方法（可带任意大小写，比较时不敏感），必填
     * @param rawPath 路径，可自带查询串（/order/abc?from=cart），必填
     * @param headers 显式请求头（头名不敏感、值敏感），可空
     * @param query   显式查询参数（名值都敏感），可空；与路径里自带查询串合并、同名覆盖
     */
    public static SimpleMatchableRequest of(String method, String rawPath,
                                            Map<String, String> headers,
                                            Map<String, List<String>> query) {
        if (method == null || method.isBlank()) {
            throw new BizException("排查请求必须带 method（如 GET/POST）");
        }
        if (rawPath == null || rawPath.isBlank()) {
            throw new BizException("排查请求必须带 path（如 /order/abc）");
        }
        String trimmedPath = rawPath.trim();

        TreeMap<String, String> headerMap =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    headerMap.putIfAbsent(k.trim(), v);
                }
            });
        }

        // 1) 先吃路径里自带的查询串，2) 再并入显式参数（同名覆盖）
        Map<String, List<String>> params = new LinkedHashMap<>();
        String pathOnly = splitPathAndQuery(trimmedPath, params);
        if (query != null) {
            query.forEach((k, values) -> {
                if (k != null && !k.isBlank() && values != null) {
                    List<String> kept = new ArrayList<>();
                    for (String v : values) {
                        if (v != null) {
                            kept.add(v);
                        }
                    }
                    params.put(k.trim(), kept);
                }
            });
        }
        if (pathOnly.isEmpty() || !pathOnly.startsWith("/")) {
            throw new BizException("排查请求的 path 必须是以 / 开头的绝对路径，收到的是：" + rawPath);
        }
        return new SimpleMatchableRequest(method.trim().toUpperCase(Locale.ROOT), pathOnly,
                headerMap, params);
    }

    /** 把 /order/abc?a=1&amp;b=2 拆成路径 /order/abc，并把查询串解析进 out（保留出现顺序）。 */
    private static String splitPathAndQuery(String rawPath, Map<String, List<String>> out) {
        int q = rawPath.indexOf('?');
        if (q < 0) {
            return rawPath;
        }
        String path = rawPath.substring(0, q);
        parseQuery(rawPath.substring(q + 1), out);
        return path;
    }

    /** 按 application/x-www-form-urlencoded 规则解析查询串（名值都做百分号解码）。 */
    private static void parseQuery(String queryString, Map<String, List<String>> out) {
        if (queryString.isEmpty()) {
            return;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawName = eq < 0 ? pair : pair.substring(0, eq);
            String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
            String name = decode(rawName);
            String value = decode(rawValue);
            if (name.isEmpty()) {
                continue;
            }
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 遇到非法百分号转义就按原样保留，别因为一笔畸形查询把整个排查请求打挂
            return s;
        }
    }

    /** 给排查结果回显用：规范后的查询参数视图。 */
    public Map<String, List<String>> queryView() {
        return query;
    }

    /** 给排查结果回显用：规范后的请求头视图（头名不敏感存储）。 */
    public Map<String, String> headerView() {
        return headers;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String header(String name) {
        return headers.get(name);
    }

    @Override
    public List<String> queryParams(String name) {
        return query.get(name);
    }
}
