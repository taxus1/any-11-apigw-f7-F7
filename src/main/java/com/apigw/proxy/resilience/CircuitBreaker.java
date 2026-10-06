package com.apigw.proxy.resilience;

import com.apigw.domain.route.CircuitBreakerPolicy;

import java.util.BitSet;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 单个「路由 × 目标上游」的本地熔断器（纯内存、各台网关各管各，不做集群共享）。
 *
 * <pre>
 *   CLOSED ──窗口内失败达门槛（次数 或 比例）──▶ OPEN
 *   OPEN   ──歇满 openWaitMs，放行试探────────▶ HALF_OPEN
 *   HALF_OPEN ──试探失败──────────────────────▶ OPEN（再歇一轮）
 *   HALF_OPEN ──连续试探成功 successThreshold 笔─▶ CLOSED（恢复放量）
 * </pre>
 *
 * 并发口径：所有状态读写都在 synchronized 方法内完成（临界区都是纳秒级内存运算，
 * 不碰 IO，锁竞争开销可忽略）；试探放行的「随机抽样」通过可注入的 {@code percentRandom}
 * 决定，生产用随机数、单元测试可钉死。半开时同一时刻只放一笔试探在飞，其余快速失败，
 * 不会在探测期把一堆请求压给尚未确认恢复的上游。
 *
 * 记什么算失败/成功由 {@link UpstreamOutcomes} 统一判定，本类只负责状态机，
 * 因此 4xx 绝不会把闸扳开（4xx 在上层按成功记账）。
 */
public class CircuitBreaker {

    public enum State {CLOSED, OPEN, HALF_OPEN}

    /** 入口裁决：放行（可能是试探）还是痛快失败；失败时带上建议的 Retry-After 毫秒。 */
    public record Admission(boolean allowed, boolean trial, long retryAfterMillis) {
        static Admission pass(boolean trial) {
            return new Admission(true, trial, 0);
        }

        static Admission rejected(long retryAfterMillis) {
            return new Admission(false, false, Math.max(0, retryAfterMillis));
        }
    }

    private final String name;
    private final CircuitBreakerPolicy config;
    private final LongSupplier clockMillis;
    /** 返回 0..99 的均匀随机数；小于 trialFraction 即放行该试探。 */
    private final IntSupplier percentRandom;

    private State state = State.CLOSED;

    // CLOSED 滑动窗口：定长环，true = 该笔失败
    private final BitSet failureRing;
    private int windowFilled;
    private int ringHead;

    // OPEN
    private long openedAtMillis;

    // HALF_OPEN
    private int consecutiveSuccesses;
    private int probesInFlight;

    public CircuitBreaker(String name, CircuitBreakerPolicy config) {
        this(name, config, System::currentTimeMillis,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextInt(100));
    }

    CircuitBreaker(String name, CircuitBreakerPolicy config,
                   LongSupplier clockMillis, IntSupplier percentRandom) {
        this.name = name;
        this.config = config;
        this.clockMillis = clockMillis;
        this.percentRandom = percentRandom;
        this.failureRing = new BitSet(config.getWindowSize());
    }

    /**
     * 请求进门时的裁决。必须在打上游<b>之前</b>调用：
     * - CLOSED：放行（非试探）；
     * - OPEN 且没歇够：拒绝，带还需等待的毫秒数；
     * - OPEN 且歇够了：翻 HALF_OPEN，放第一笔试探（trial=true）；
     * - HALF_OPEN：已有试探在飞则拒绝（不堆并发探测）；否则按 trialFraction 抽样放行。
     */
    public synchronized Admission admit() {
        long now = clockMillis.getAsLong();
        if (state == State.CLOSED) {
            return Admission.pass(false);
        }
        if (state == State.OPEN) {
            long waited = now - openedAtMillis;
            if (waited < config.getOpenWaitMs()) {
                return Admission.rejected(config.getOpenWaitMs() - waited);
            }
            // 歇够了：进入半开，第一笔无条件放过去试探
            toHalfOpen();
            probesInFlight = 1;
            return Admission.pass(true);
        }
        // HALF_OPEN
        if (probesInFlight > 0) {
            // 已有试探在飞：别再堆，痛快失败（不带 Retry-After，没有固定等待时长）
            return Admission.rejected(0);
        }
        if (percentRandom.getAsInt() < config.getTrialFraction()) {
            probesInFlight = 1;
            return Admission.pass(true);
        }
        return Admission.rejected(0);
    }

    /**
     * 记一笔成功（2xx/3xx/4xx 都算上游正常，见 {@link UpstreamOutcomes}）。
     *
     * @param trial 这笔是不是半开试探（由 {@link #admit} 的返回告知）
     */
    public synchronized void recordSuccess(boolean trial) {
        if (state == State.HALF_OPEN && trial) {
            probesInFlight = Math.max(0, probesInFlight - 1);
            consecutiveSuccesses++;
            if (consecutiveSuccesses >= config.getSuccessThreshold()) {
                toClosed();
            }
            return;
        }
        if (state == State.CLOSED) {
            pushWindow(false);
        }
        // OPEN 期理论上不会有在飞请求（都被拒了）；意外在飞的成功不改变等待，忽略
    }

    /** 记一笔失败（传输故障/超时/5xx）。 */
    public synchronized void recordFailure(boolean trial) {
        if (state == State.HALF_OPEN && trial) {
            // 试探还是不行：立刻回去再歇一轮，不数成功数
            probesInFlight = Math.max(0, probesInFlight - 1);
            toOpen();
            return;
        }
        if (state == State.CLOSED) {
            pushWindow(true);
            if (tripped()) {
                toOpen();
            }
        }
    }

    /** 当前状态（监控/测试看一眼）。 */
    public synchronized State state() {
        return state;
    }

    /** 熔断器名字（路由编号@上游），日志用。 */
    public String name() {
        return name;
    }

    /** 配置是否与另一份相同（路由热刷新后决定要不要换新熔断器）。 */
    boolean sameConfig(CircuitBreakerPolicy other) {
        return config.getWindowSize().equals(other.getWindowSize())
                && config.getMinimumNumberOfCalls().equals(other.getMinimumNumberOfCalls())
                && config.getFailureRateThreshold().equals(other.getFailureRateThreshold())
                && config.getMinFailureCount().equals(other.getMinFailureCount())
                && config.getOpenWaitMs().equals(other.getOpenWaitMs())
                && config.getTrialFraction().equals(other.getTrialFraction())
                && config.getSuccessThreshold().equals(other.getSuccessThreshold());
    }

    // ---- 内部状态迁移 ----

    private void pushWindow(boolean failed) {
        int size = config.getWindowSize();
        if (windowFilled < size) {
            failureRing.set(windowFilled, failed);
            windowFilled++;
        } else {
            failureRing.set(ringHead, failed);
            ringHead = (ringHead + 1) % size;
        }
    }

    /** 失败次数 或 失败比例任一达门槛（样本不足 minimumNumberOfCalls 时不比比例）。 */
    private boolean tripped() {
        int failures = failureRing.cardinality();
        if (failures >= config.getMinFailureCount()) {
            return true;
        }
        if (windowFilled >= config.getMinimumNumberOfCalls() && windowFilled > 0) {
            long ratePct = Math.round(100.0 * failures / windowFilled);
            return ratePct >= config.getFailureRateThreshold() && failures > 0;
        }
        return false;
    }

    private void toOpen() {
        state = State.OPEN;
        openedAtMillis = clockMillis.getAsLong();
        consecutiveSuccesses = 0;
        probesInFlight = 0;
    }

    private void toHalfOpen() {
        state = State.HALF_OPEN;
        consecutiveSuccesses = 0;
        probesInFlight = 0;
    }

    private void toClosed() {
        state = State.CLOSED;
        failureRing.clear();
        windowFilled = 0;
        ringHead = 0;
        consecutiveSuccesses = 0;
        probesInFlight = 0;
    }
}
