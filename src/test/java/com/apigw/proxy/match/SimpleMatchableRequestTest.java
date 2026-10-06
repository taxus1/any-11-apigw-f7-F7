package com.apigw.proxy.match;

import com.apigw.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排查用「手工请求」组装测试，重点钉住口径：
 * - 路径里的查询串会被解析成查询参数，路径本身不含 ?；
 * - 查询只认 URL 那一串（这里体现为 path 自带串 + 显式 query 同一来源），名值大小写敏感；
 * - 头名大小写不敏感、值敏感，同名头取第一个；
 * - 方法归一为大写；path/method 缺失或以非 / 开头要报清楚。
 */
class SimpleMatchableRequestTest {

    @Test
    void parsesQueryFromPath_andStripsQueryFromPath() {
        SimpleMatchableRequest r = SimpleMatchableRequest.of("get", "/order/abc?from=cart&x=1", null, null);
        assertEquals("GET", r.method());
        assertEquals("/order/abc", r.path());
        assertEquals(List.of("cart"), r.queryParams("from"));
        assertEquals(List.of("1"), r.queryParams("x"));
        assertNull(r.queryParams("FROM"), "参数名大小写敏感");
    }

    @Test
    void percentDecodesQueryNameAndValue() {
        SimpleMatchableRequest r = SimpleMatchableRequest.of("GET", "/x?name=a%20b&%E4%B8%AD%E6%96%87=v",
                null, null);
        assertEquals(List.of("a b"), r.queryParams("name"));
        assertEquals(List.of("v"), r.queryParams("中文"));
    }

    @Test
    void explicitQueryMergesAndOverridesPathQuery() {
        SimpleMatchableRequest r = SimpleMatchableRequest.of("GET", "/x?from=cart&keep=1", null,
                Map.of("from", List.of("buy"), "added", List.of("2")));
        // 同名以显式 query 覆盖
        assertEquals(List.of("buy"), r.queryParams("from"));
        assertEquals(List.of("1"), r.queryParams("keep"));
        assertEquals(List.of("2"), r.queryParams("added"));
    }

    @Test
    void repeatedQueryParamsKeepAllValues() {
        SimpleMatchableRequest r = SimpleMatchableRequest.of("GET", "/x?id=1&id=2&id=3", null, null);
        assertEquals(List.of("1", "2", "3"), r.queryParams("id"));
    }

    @Test
    void headerNameCaseInsensitive_valueCaseSensitive_firstWins() {
        Map<String, String> headers = new TreeMap<>();
        headers.put("X-Caller", "web");
        headers.put("x-caller", "WEB"); // 同名（不敏感）只留先放入的
        SimpleMatchableRequest r = SimpleMatchableRequest.of("GET", "/x", headers, null);
        assertEquals("web", r.header("x-caller"));
        assertEquals("web", r.header("X-CALLER"));
    }

    @Test
    void missingMethodOrPath_isRejectedClearly() {
        assertThrows(BizException.class, () -> SimpleMatchableRequest.of(null, "/x", null, null));
        assertThrows(BizException.class, () -> SimpleMatchableRequest.of("GET", "  ", null, null));
        BizException bad = assertThrows(BizException.class,
                () -> SimpleMatchableRequest.of("GET", "order/x", null, null));
        assertTrue(bad.getMessage().contains("以 / 开头"));
    }

    @Test
    void malformedPercentEscapeDoesNotBlowUp() {
        SimpleMatchableRequest r = SimpleMatchableRequest.of("GET", "/x?k=%zz", null, null);
        assertEquals("%zz", r.queryParams("k").get(0));
    }
}
