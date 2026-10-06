package com.apigw.proxy.route;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置切换前的本机短暂栅栏。
 *
 * <p>进入栅栏后，新请求先等待，已经拿到旧快照的在途请求继续使用旧快照；切换完成后统一放行。
 * 全程基于反应式信号，不阻塞 Netty 线程。等待必须有超时，避免协调器异常时把请求无限挂住。
 */
public class ActivationGate {

    private final AtomicReference<WaitPoint> current = new AtomicReference<>();
    private final Duration timeout;

    public ActivationGate(Duration timeout) {
        this.timeout = timeout;
    }

    public void arm() {
        current.set(new WaitPoint());
    }

    public void open() {
        WaitPoint point = current.getAndSet(null);
        if (point != null) {
            point.open();
        }
    }

    public boolean armed() {
        return current.get() != null;
    }

    /** 若栅栏未激活立即完成；激活时最多等到 {@code timeout}，超时后交由上层继续旧快照并中止本轮。 */
    public Mono<Void> awaitOpen() {
        WaitPoint point = current.get();
        if (point == null) {
            return Mono.empty();
        }
        return point.onOpen().timeout(timeout).onErrorResume(err -> Mono.empty());
    }

    private static final class WaitPoint {
        private final reactor.core.publisher.Sinks.Empty<Void> sink =
                reactor.core.publisher.Sinks.empty();

        Mono<Void> onOpen() {
            return sink.asMono();
        }

        void open() {
            sink.tryEmitEmpty();
        }
    }
}
