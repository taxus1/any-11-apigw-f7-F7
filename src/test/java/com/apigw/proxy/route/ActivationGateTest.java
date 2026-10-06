package com.apigw.proxy.route;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

class ActivationGateTest {

    @Test
    void notArmed_completesImmediately() {
        ActivationGate gate = new ActivationGate(Duration.ofMillis(50));
        StepVerifier.create(gate.awaitOpen())
                .verifyComplete();
    }

    @Test
    void armed_requestWaitsUntilOpen() {
        ActivationGate gate = new ActivationGate(Duration.ofSeconds(2));
        gate.arm();

        StepVerifier.create(gate.awaitOpen()
                        .doOnSubscribe(subscription ->
                                reactor.core.publisher.Mono.delay(Duration.ofMillis(50))
                                        .doOnNext(x -> gate.open()).subscribe()))
                .expectSubscription()
                .thenConsumeWhile(x -> true)
                .verifyComplete();
    }

    @Test
    void openTimeout_doesNotHangCaller() {
        ActivationGate gate = new ActivationGate(Duration.ofMillis(20));
        gate.arm();
        StepVerifier.create(gate.awaitOpen())
                .verifyComplete();
    }
}
