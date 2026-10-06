package com.apigw.domain.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重试资格判定单测。核心安全口径：
 * - GET/HEAD/OPTIONS 默认可重试；
 * - POST/PUT/PATCH/DELETE 默认一律不重试（下单、支付不能来第二遍）；
 * - PUT/DELETE 只有运营在该路由显式声明幂等才可重试；
 * - POST 仅在带了约定幂等键头时可重试（按内部约定，不看 URL）；
 * - maxAttempts=1 时即使安全方法也不重试。
 */
class RetryPolicyTest {

    private RetryPolicy policy(int maxAttempts) {
        RetryPolicy p = RetryPolicy.defaults();
        p.setMaxAttempts(maxAttempts);
        return p;
    }

    @Test
    void safeMethods_areRetryable_byDefault() {
        RetryPolicy p = policy(2);
        assertThat(p.isRetryable("GET", false)).isTrue();
        assertThat(p.isRetryable("HEAD", false)).isTrue();
        assertThat(p.isRetryable("OPTIONS", false)).isTrue();
    }

    @Test
    void postPutPatchDelete_areNotRetryable_byDefault_evenIfPathLooksLikeQuery() {
        RetryPolicy p = policy(3);
        // 关键：判定只看方法，路径 /order/query 完全不参与
        for (String m : List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertThat(p.isRetryable(m, false))
                    .as("方法 %s 默认不许重试", m).isFalse();
        }
    }

    @Test
    void declaredIdempotentMethods_becomeRetryable_butNotPost() {
        RetryPolicy p = policy(2);
        p.setIdempotentMethods(List.of("PUT", "DELETE"));
        p.normalizeAndValidate();
        assertThat(p.isRetryable("PUT", false)).isTrue();
        assertThat(p.isRetryable("DELETE", false)).isTrue();
        // POST 不会因为方法列表而放行
        assertThat(p.isRetryable("POST", false)).isFalse();
        assertThat(p.isRetryable("PATCH", false)).isFalse();
    }

    @Test
    void post_isRetryable_onlyWithIdempotencyKeyHeader_present() {
        RetryPolicy p = policy(2);
        p.setIdempotencyKeyHeader("Idempotency-Key");
        // 没带键：POST 不重试
        assertThat(p.isRetryable("POST", false)).isFalse();
        // 带了非空键：上游按键去重，可安全重投
        assertThat(p.isRetryable("POST", true)).isTrue();
    }

    @Test
    void idempotencyKey_doesNotOverride_whenNoHeaderConfigured() {
        RetryPolicy p = policy(2);
        // 路由没约定幂等键头：就算请求带了某个头也不认
        assertThat(p.isRetryable("POST", true)).isFalse();
    }

    @Test
    void singleAttempt_meansNoRetry_evenForGet() {
        RetryPolicy p = policy(1);
        assertThat(p.isRetryable("GET", false)).isFalse();
    }

    @Test
    void methodNameIsCaseInsensitive_onConfig() {
        RetryPolicy p = policy(2);
        p.setIdempotentMethods(List.of("delete", "Put"));
        p.normalizeAndValidate();
        assertThat(p.getIdempotentMethods()).containsExactly("DELETE", "PUT");
    }

    @Test
    void rejectsAttemptsOutOfRange() {
        RetryPolicy p = policy(6);
        assertThatThrownBy(p::normalizeAndValidate)
                .isInstanceOf(com.apigw.common.exception.BizException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void totalTimeoutShorterThanBackoff_isAllowed_andMeansNoRetryAtRuntime() {
        // 总时限短到补发来不及发出是合法配置：归一化不报错，运行时换发前检查自然只打一发
        RetryPolicy p = policy(3);
        p.setBackoffMs(500L);
        p.setTotalTimeoutMs(100L);
        p.normalizeAndValidate();
        assertThat(p.getTotalTimeoutMs()).isEqualTo(100L);
    }
}
