package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 一条路由的重试策略（随路由整树配置、一条一配；不配置 = 这条路由不重试，旧链路零变化）。
 *
 * <p><b>什么请求能重试（口径必须可靠，不能看 URL 像不像）：</b>
 * <ol>
 *   <li><b>方法安全</b>：HTTP 方法本身就是「安全方法（safe）」的 {@code GET / HEAD / OPTIONS}
 *       默认可重试——语义上它们只读、重复调用不会产生第二个副作用。
 *       注意 {@code PUT/DELETE} 虽然常被当作幂等，但「幂等」不等于「重试安全」：
 *       DELETE 重试一般无害，PUT 取决于实现（可能是计数器、可能是状态推进），
 *       所以默认<b>不</b>重试；只有运营在这条路由上明确把方法加进 {@link #idempotentMethods}
 *       才重试。{@code POST/PATCH} 默认永远不重试——下单、支付就在这里，再来一遍可能多扣钱。</li>
 *   <li><b>内部约定：幂等键头</b>。当且仅当路由配了 {@link #idempotencyKeyHeader}
 *       且请求确实带了该头（非空）时，哪怕方法是 POST 也允许重试——带了业务幂等键，
 *       上游就该按同一个键去重，重复投递不会产生第二笔订单/扣款。
 *       这是「内部约定」而不是 URL 猜测：{@code /order/query} 不会因为名字像查询就被重试，
 *       {@code /order/create} 带了约定的幂等键也能安全重试。</li>
 * </ol>
 *
 * <p><b>什么情况才重试：</b>只有「上游病了」才重试——连接失败、读写 IO 故障、超时，
 * 以及上游回的 <b>5xx</b>。4xx 不重试（请求本身有问题，再来一遍还是错，纯粹放大压力）。
 * 501 也算上游服务端明确表示「不支持」，重试无意义，故 501 不重试。
 *
 * <p><b>总量约束：</b>{@link #maxAttempts} 是「最多打几发」（含首发），{@link #backoffMs}
 * 是两发之间的固定等待；整个重试过程（等待 + 各发耗时）不得超过 {@link #totalTimeoutMs}，
 * 到点没成功也不再补发。重试全失败就把最后一次的真实结果/故障如实返回，绝不无限试。
 */
@Getter
@Setter
public class RetryPolicy {

    /** RFC 9110 安全方法：只读、无副作用，默认可重试。全大写比对。 */
    public static final List<String> SAFE_METHODS = List.of("GET", "HEAD", "OPTIONS");

    /** 头名格式（token 字符），防配进乱七八糟的值。 */
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}");
    private static final Pattern METHOD_NAME = Pattern.compile("[A-Z]{1,16}");

    /** 最多打几发（含首发）：2 = 首发 + 最多 1 次重试。 */
    private Integer maxAttempts;

    /** 两发之间的固定等待（毫秒）。 */
    private Long backoffMs;

    /** 整个请求（含全部重试与等待）的总时限（毫秒），到点不再补发。 */
    private Long totalTimeoutMs;

    /**
     * 除安全方法外，这条路由上明确「重试安全」的方法（如 PUT/DELETE），大写。
     * 运营按「这条路由对应的上游动作确实重复无害」逐条开通，不按 URL 猜。
     */
    private List<String> idempotentMethods = new ArrayList<>();

    /**
     * 业务幂等键请求头名：配了它、且请求带了非空该头，则即使 POST/PATCH 也允许重试
     * （上游按幂等键去重）。null/空 = 不接受任何「带键即重试」的例外。
     */
    private String idempotencyKeyHeader;

    public static RetryPolicy defaults() {
        RetryPolicy p = new RetryPolicy();
        p.maxAttempts = 2;
        p.backoffMs = 100L;
        p.totalTimeoutMs = 15_000L;
        p.idempotentMethods = new ArrayList<>();
        p.idempotencyKeyHeader = null;
        return p;
    }

    /**
     * 规范化并校验。返回 this（便于链式/直接使用）。
     */
    public RetryPolicy normalizeAndValidate() {
        if (maxAttempts == null) {
            maxAttempts = 2;
        }
        if (backoffMs == null) {
            backoffMs = 100L;
        }
        if (totalTimeoutMs == null) {
            totalTimeoutMs = 15_000L;
        }
        if (idempotentMethods == null) {
            idempotentMethods = new ArrayList<>();
        }

        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new BizException("重试次数 maxAttempts 必须在 1~5 之间（含首发，2 = 最多再试 1 次），收到："
                    + maxAttempts);
        }
        if (backoffMs < 0 || backoffMs > 30_000L) {
            throw new BizException("重试间隔 backoffMs 必须在 0~30000ms 之间，收到：" + backoffMs);
        }
        if (totalTimeoutMs < 1 || totalTimeoutMs > 600_000L) {
            throw new BizException("重试总时限 totalTimeoutMs 必须在 1ms~600000ms(10分钟) 之间，收到："
                    + totalTimeoutMs);
        }
        // 注意：不强制 totalTimeoutMs >= backoff × 次数。总时限短于「首发 + 一次等待」时，
        // 运行时的换发前检查会让任何补发都来不及发出（等于只打一发），这是有效且有用的配置
        // （例如只想用一个很短的整体时限兜底、并不真的指望重试）。

        List<String> normalized = new ArrayList<>();
        for (String raw : idempotentMethods) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String m = raw.trim().toUpperCase(Locale.ROOT);
            if (!METHOD_NAME.matcher(m).matches()) {
                throw new BizException("可重试方法名不合法：" + raw + "（只允许 1~16 位大写字母）");
            }
            // 安全方法本来就能重试，写进来属于重复配置，直接忽略并去重
            if (SAFE_METHODS.contains(m) || normalized.contains(m)) {
                continue;
            }
            normalized.add(m);
        }
        this.idempotentMethods = normalized;

        if (idempotencyKeyHeader != null) {
            String h = idempotencyKeyHeader.trim();
            if (h.isEmpty()) {
                this.idempotencyKeyHeader = null;
            } else if (!HEADER_NAME.matcher(h).matches()) {
                throw new BizException("幂等键头名 idempotencyKeyHeader 不合法：" + idempotencyKeyHeader);
            } else {
                this.idempotencyKeyHeader = h;
            }
        }
        return this;
    }

    /**
     * 判定一个进来的请求是否允许重试。依据只有「方法 + 内部幂等键约定」，绝不看路径。
     *
     * @param method              请求方法（调用方已保证非空、大写）
     * @param hasIdempotencyKey   请求是否带了非空的约定幂等键头（由过滤器按当前配置现查）
     */
    public boolean isRetryable(String method, boolean hasIdempotencyKey) {
        if (maxAttempts == null || maxAttempts <= 1) {
            return false;
        }
        if (SAFE_METHODS.contains(method)) {
            return true;
        }
        if (idempotentMethods != null && idempotentMethods.contains(method)) {
            return true;
        }
        // 带业务幂等键：上游按键去重，POST/PATCH 也可安全重投
        return hasIdempotencyKey && idempotencyKeyHeader != null;
    }
}
