package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

/**
 * 一条路由的「韧性」配置：熔断 + 重试。两者各自独立开关、独立参数，互不绑定：
 * 可以只开熔断、只开重试、都开或都不开。没配（null）= 这条路由两者都不介入，
 * 与功能上线前的旧转发链路行为完全一致。
 *
 * 开关口径：
 * - {@link #circuitBreakerEnabled}=1 且 {@link #circuitBreaker} 非空 → 该路由启用熔断；
 * - {@link #retryEnabled}=1 且 {@link #retry} 非空 → 该路由启用重试。
 */
@Getter
@Setter
public class ResiliencePolicy {

    /** 1 启用熔断 / 0 停用（缺省 0）。 */
    private Integer circuitBreakerEnabled;

    private CircuitBreakerPolicy circuitBreaker = new CircuitBreakerPolicy();

    /** 1 启用重试 / 0 停用（缺省 0）。 */
    private Integer retryEnabled;

    private RetryPolicy retry = new RetryPolicy();

    public static ResiliencePolicy disabled() {
        ResiliencePolicy p = new ResiliencePolicy();
        p.circuitBreakerEnabled = 0;
        p.retryEnabled = 0;
        return p;
    }

    public boolean circuitBreakerOn() {
        return circuitBreakerEnabled != null && circuitBreakerEnabled == 1;
    }

    public boolean retryOn() {
        return retryEnabled != null && retryEnabled == 1;
    }

    /**
     * 按开关校验并归一化对应子配置；关着的一侧即使传了参数也不生效（不浪费精力校它）。
     */
    public void normalizeAndValidate() {
        if (circuitBreakerEnabled == null) {
            circuitBreakerEnabled = 0;
        }
        if (retryEnabled == null) {
            retryEnabled = 0;
        }
        if (circuitBreakerEnabled != 0 && circuitBreakerEnabled != 1) {
            throw new BizException("熔断开关只能是 0（停用）或 1（启用），收到：" + circuitBreakerEnabled);
        }
        if (retryEnabled != 0 && retryEnabled != 1) {
            throw new BizException("重试开关只能是 0（停用）或 1（启用），收到：" + retryEnabled);
        }
        if (circuitBreakerOn()) {
            if (circuitBreaker == null) {
                circuitBreaker = CircuitBreakerPolicy.defaults();
            }
            circuitBreaker.normalizeAndValidate();
        }
        if (retryOn()) {
            if (retry == null) {
                retry = RetryPolicy.defaults();
            }
            retry.normalizeAndValidate();
        }
    }
}
