package com.apigw.proxy.resilience;

import com.apigw.domain.route.CircuitBreakerPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断状态机单测（可控时钟 + 钉死抽样随机数，不打真实网络）。
 * 覆盖：失败次数跳闸、失败比例跳闸、样本不足不跳、成功不跳、OPEN 期快速失败、
 * 歇够后半开放试探、试探成功恢复、试探失败再开、半开不堆并发探测。
 */
class CircuitBreakerTest {

    private final AtomicLong clock = new AtomicLong(0);
    private final AtomicInteger rnd = new AtomicInteger(0);

    private CircuitBreaker breaker(CircuitBreakerPolicy p) {
        return new CircuitBreaker("test@upstream", p, clock::get, rnd::get);
    }

    private CircuitBreakerPolicy policy(int window, int minCalls, int rate, int minFail,
                                        long waitMs, int trialPct, int successThreshold) {
        CircuitBreakerPolicy p = new CircuitBreakerPolicy();
        p.setWindowSize(window);
        p.setMinimumNumberOfCalls(minCalls);
        p.setFailureRateThreshold(rate);
        p.setMinFailureCount(minFail);
        p.setOpenWaitMs(waitMs);
        p.setTrialFraction(trialPct);
        p.setSuccessThreshold(successThreshold);
        return p;
    }

    @Test
    void closed_passesEverything_andSuccessesDoNotTrip() {
        CircuitBreaker cb = breaker(policy(10, 5, 50, 5, 1000, 100, 1));
        for (int i = 0; i < 20; i++) {
            assertThat(cb.admit().allowed()).isTrue();
            cb.recordSuccess(false);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opens_whenConsecutiveFailureCountReached() {
        CircuitBreaker cb = breaker(policy(20, 5, 50, 5, 1000, 100, 1));
        for (int i = 0; i < 4; i++) {
            cb.admit();
            cb.recordFailure(false);
            assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
        cb.admit();
        cb.recordFailure(false);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void opens_whenFailureRateReached_withEnoughSamples() {
        // 窗口 10、最小样本 5、比例门槛 50%、绝对次数门槛设高（10）避免次数先触发
        CircuitBreaker cb = breaker(policy(10, 5, 50, 10, 1000, 100, 1));
        // 3 成 2 败：样本 5，失败率 40%，不跳
        for (int i = 0; i < 3; i++) {
            cb.admit();
            cb.recordSuccess(false);
        }
        for (int i = 0; i < 2; i++) {
            cb.admit();
            cb.recordFailure(false);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 再来 1 败：3 成 3 败 = 50%，跳
        cb.admit();
        cb.recordFailure(false);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void doesNotCompareRate_belowMinimumSamples_evenIfRateIs100Percent() {
        // 最小样本 5、绝对次数门槛设 5：1/1=100% 也不该靠比例跳
        CircuitBreaker cb = breaker(policy(20, 5, 50, 5, 1000, 100, 1));
        cb.admit();
        cb.recordFailure(false);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void open_rejectsImmediatelyWithRetryAfter_andDoesNotNeedProbe() {
        CircuitBreaker cb = breaker(policy(20, 5, 50, 1, 10_000, 100, 1));
        cb.admit();
        cb.recordFailure(false);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.set(1_000);
        CircuitBreaker.Admission a = cb.admit();
        assertThat(a.allowed()).isFalse();
        // 还需等约 9s
        assertThat(a.retryAfterMillis()).isBetween(8_900L, 9_000L);
    }

    @Test
    void afterWait_letsOneProbeThrough_andSuccessCloses() {
        CircuitBreaker cb = breaker(policy(20, 5, 50, 1, 10_000, 100, 1));
        cb.admit();
        cb.recordFailure(false);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.set(10_001);
        CircuitBreaker.Admission probe = cb.admit();
        assertThat(probe.allowed()).isTrue();
        assertThat(probe.trial()).isTrue();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 半开期间再来的请求：已有试探在飞，不许叠加
        assertThat(cb.admit().allowed()).isFalse();

        // 试探成功 → 恢复放量
        cb.recordSuccess(true);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.admit().allowed()).isTrue();
    }

    @Test
    void failedProbe_reopensAndWaitsAgain() {
        CircuitBreaker cb = breaker(policy(20, 5, 50, 1, 1_000, 100, 1));
        cb.admit();
        cb.recordFailure(false);
        clock.set(1_001);
        CircuitBreaker.Admission probe = cb.admit();
        assertThat(probe.trial()).isTrue();

        // 试探失败 → 立刻回 OPEN，再歇一轮
        cb.recordFailure(true);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        // 刚重开，等待时间重置
        clock.set(1_500);
        assertThat(cb.admit().allowed()).isFalse();
        clock.set(2_002);
        assertThat(cb.admit().trial()).isTrue();
    }

    @Test
    void successThresholdRequiresMultipleConsecutiveProbeSuccesses() {
        CircuitBreaker cb = breaker(policy(20, 5, 50, 1, 1_000, 100, 3));
        cb.admit();
        cb.recordFailure(false); // → OPEN
        clock.set(1_001);
        assertThat(cb.admit().trial()).isTrue(); // → HALF_OPEN
        cb.recordSuccess(true);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        // 第二笔：无在飞试探，抽样 100% 放行
        rnd.set(0);
        CircuitBreaker.Admission second = cb.admit();
        assertThat(second.trial()).isTrue();
        cb.recordSuccess(true);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        // 第三笔成功才恢复
        rnd.set(0);
        assertThat(cb.admit().trial()).isTrue();
        cb.recordSuccess(true);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void windowSlides_oldFailuresAgeOut() {
        // 窗口 3、绝对门槛 3、比例门槛钉到 100（本用例只验证「失败次数」与滑动，不让比例提前触发）
        CircuitBreakerPolicy p = policy(3, 5, 100, 3, 1000, 100, 1);
        CircuitBreaker cb = breaker(p);
        for (int i = 0; i < 2; i++) {
            cb.admit();
            cb.recordFailure(false);
        }
        // 连续两败后来一个成功：窗口 [F,F,S]，不满足全败、失败数也才 2
        cb.admit();
        cb.recordSuccess(false);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        // 再来两个成功把环填满并挤掉两个旧 F：窗口依次 [F,S,S] → [S,S,S]
        cb.admit();
        cb.recordSuccess(false);
        cb.admit();
        cb.recordSuccess(false);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 反过来：最近 3 笔全败要能跳（验证环逻辑没被成功搞坏）
        for (int i = 0; i < 3; i++) {
            cb.admit();
            cb.recordFailure(false);
        }
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
