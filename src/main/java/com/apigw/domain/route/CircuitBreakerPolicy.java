package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

/**
 * 一条路由的熔断策略（随路由整树配置、一条一配；不配置 = 这条路由不熔断，旧链路零变化）。
 *
 * <p>熔断口径（为什么这样定）：
 * <ul>
 *   <li><b>只数「上游病了」，不数「请求本身有问题」。</b>
 *       计失败的只有：连接/读写出错、超时，以及上游回的 <b>5xx</b>（含 500/502/503/504 等，
 *       代表上游没把这笔请求正常处理掉）。上游回的 <b>4xx</b> 一律不算失败：
 *       4xx 说的是「这笔请求自己不合法/没权限/资源不存在」，上游进程其实活得好好的、
 *       判断也正常，把它算进失败率会被一批坏请求（比如参数错、扫不存在的 URL）误伤，
 *       把本来健康的上游误熔断。429 虽然是「限流」，但这里的口径是上游<b>主动且正常</b>
 *       地拒绝请求（不是它崩了），同样不算病。</li>
 *   <li><b>两条门槛是「或」</b>：最近 {@link #windowSize} 笔里，失败数达到
 *       {@link #minFailureCount} <i>或</i> 失败比例达到 {@link #failureRateThreshold}
 *       （且样本数已到 {@link #minimumNumberOfCalls}，样本太少不比比例，避免 1/1=100% 乱跳），
 *       任一满足即跳闸。</li>
 * </ul>
 *
 * <p>状态机（本地、每台网关各管各，不做三台共享）：
 * <pre>
 *   CLOSED  正常放量，每笔结果记入滑动窗口；门槛到了 → OPEN
 *   OPEN    等待 openWaitDuration，期间来的请求一律不打上游，直接痛快失败（503）
 *           等待时长到了之后，下一笔请求被放过去试探 → HALF_OPEN
 *   HALF_OPEN 试探请求：
 *             · 成功（2xx/3xx/4xx 都算上游正常）→ 连续成功累计到 successThreshold，
 *               认为恢复 → CLOSED，放量；
 *             · 失败（异常/超时/5xx）→ 立刻回 OPEN，再歇一个等待时长。
 *           同一时刻只放一笔试探在飞，其余请求仍按 OPEN 痛快失败，
 *           不会在半开状态下放一堆请求把病上游再压垮。
 * </pre>
 *
 * <p>{@link #trialFraction} 是「半开等待后试探流量的占比」：半开状态下除了那一笔在飞试探外，
 * 再进来的请求按该比例随机放行参与试探（0~100，默认 100 = 都允许排队试探；配 10 即只放 10%）。
 */
@Getter
@Setter
public class CircuitBreakerPolicy {

    /** 滑动窗口容量：只看最近 N 笔的成败（最近的结果最能说明上游现在的状态）。 */
    private Integer windowSize;

    /** 窗口里至少攒够多少笔才开始比失败比例；少于这个数不比比例，只比绝对失败次数。 */
    private Integer minimumNumberOfCalls;

    /** 失败比例门槛（0~100，百分比）：达到即跳闸。 */
    private Integer failureRateThreshold;

    /** 连续失败次数门槛（1~windowSize）：达到即跳闸。 */
    private Integer minFailureCount;

    /** OPEN 持续时长（毫秒）：歇够这么久才允许放试探请求。 */
    private Long openWaitMs;

    /** HALF_OPEN 试探流量占比（0~100）：半开时新请求按此比例被放过去试探。 */
    private Integer trialFraction;

    /** HALF_OPEN 连续试探成功多少笔判定恢复（默认 1：第一笔试探成功即恢复放量）。 */
    private Integer successThreshold;

    public static CircuitBreakerPolicy defaults() {
        CircuitBreakerPolicy p = new CircuitBreakerPolicy();
        p.windowSize = 20;
        p.minimumNumberOfCalls = 5;
        p.failureRateThreshold = 50;
        p.minFailureCount = 5;
        p.openWaitMs = 10_000L;
        p.trialFraction = 100;
        p.successThreshold = 1;
        return p;
    }

    /**
     * 落库/运行前的完整校验。所有字段缺省时取默认值（运营只配想改的几项）。
     */
    public void normalizeAndValidate() {
        if (windowSize == null) {
            windowSize = 20;
        }
        if (minimumNumberOfCalls == null) {
            minimumNumberOfCalls = 5;
        }
        if (failureRateThreshold == null) {
            failureRateThreshold = 50;
        }
        if (minFailureCount == null) {
            minFailureCount = 5;
        }
        if (openWaitMs == null) {
            openWaitMs = 10_000L;
        }
        if (trialFraction == null) {
            trialFraction = 100;
        }
        if (successThreshold == null) {
            successThreshold = 1;
        }

        if (windowSize < 2 || windowSize > 10_000) {
            throw new BizException("熔断窗口容量 windowSize 必须在 2~10000 之间，收到：" + windowSize);
        }
        if (minimumNumberOfCalls < 1 || minimumNumberOfCalls > windowSize) {
            throw new BizException("熔断最小样本数 minimumNumberOfCalls 必须在 1~windowSize("
                    + windowSize + ") 之间，收到：" + minimumNumberOfCalls);
        }
        if (failureRateThreshold < 1 || failureRateThreshold > 100) {
            throw new BizException("失败比例门槛 failureRateThreshold 必须是 1~100 的百分数，收到："
                    + failureRateThreshold);
        }
        if (minFailureCount < 1 || minFailureCount > windowSize) {
            throw new BizException("失败次数门槛 minFailureCount 必须在 1~windowSize("
                    + windowSize + ") 之间，收到：" + minFailureCount);
        }
        if (openWaitMs < 1 || openWaitMs > 3_600_000L) {
            throw new BizException("熔断等待时长 openWaitMs 必须在 1ms~3600000ms(1小时) 之间，收到："
                    + openWaitMs);
        }
        if (trialFraction < 0 || trialFraction > 100) {
            throw new BizException("试探流量占比 trialFraction 必须是 0~100 的百分数，收到：" + trialFraction);
        }
        if (successThreshold < 1 || successThreshold > 100) {
            throw new BizException("半开恢复成功数 successThreshold 必须在 1~100 之间，收到："
                    + successThreshold);
        }
    }
}
