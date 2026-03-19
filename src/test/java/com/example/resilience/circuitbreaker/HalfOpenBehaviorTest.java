package com.example.resilience.circuitbreaker;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HalfOpenBehaviorTest extends ExampleTestBase {

    private CircuitBreaker openCircuit(CircuitBreakerConfig config) {
        CircuitBreaker cb = CircuitBreaker.of("ho-" + UUID.randomUUID(), config);
        TestLogger.attach(cb);
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 5; i++) {
            String key = "pk_ho_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_ho", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        return cb;
    }

    /**
     * HALF_OPEN에서 permittedNumberOfCalls를 초과하는 요청은 거절되는 것을 검증한다.
     *
     * 흐름:
     *   OPEN → waitDuration 후 HALF_OPEN 전환
     *   → SLOW 모드에서 permitted 3건을 병렬로 시작 (슬롯 점유)
     *   → 4번째 요청 → CallNotPermittedException 발생
     *
     * 핵심:
     *   HALF_OPEN은 제한된 수의 요청만 통과시켜 서버 복구 여부를 탐색한다.
     *   초과 요청은 즉시 거절하여 불안정한 서버에 과부하를 주지 않는다.
     */
    @Test
    void HALF_OPEN에서_permittedNumberOfCalls_초과_요청은_거절된다() throws Exception {
        CircuitBreaker cb = openCircuit(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .slidingWindowSize(5)
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());

        // SLOW 모드로 전환 → permitted 호출이 오래 걸리게
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "5000", "slowMaxMs", "5000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 10000); // readTimeout=10s

        // HALF_OPEN 전환 대기
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 3건을 병렬로 시작 (SLOW 5s → 슬롯 점유)
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch started = new CountDownLatch(3);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                started.countDown();
                String key = "pk_ho_perm_" + idx;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_ho_perm", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }));
        }

        started.await(3, TimeUnit.SECONDS);
        Thread.sleep(500); // permitted 호출이 진행 중임을 보장

        // 4번째 요청 → CallNotPermittedException
        TestLogger.summary(cb);
        Supplier<Map<String, Object>> fourth = CircuitBreaker.decorateSupplier(cb,
                () -> paymentClient.confirm("pk_ho_4th", "order_ho_4th", 10000));
        assertThatThrownBy(fourth::get).isInstanceOf(CallNotPermittedException.class);

        for (Future<?> f : futures) { try { f.get(15, TimeUnit.SECONDS); } catch (Exception ignored) {} }
        executor.shutdown();
    }

    /**
     * maxWaitDurationInHalfOpenState 기본값(0)이면 무한 대기하는 것을 검증한다.
     *
     * 흐름:
     *   OPEN → HALF_OPEN 전환 → SLOW(5s) 호출 1건 시작
     *   → 3초 후에도 여전히 HALF_OPEN (maxWait=0 → 무한 대기)
     *
     * 문제:
     *   기본값 0은 "무한 대기"를 의미한다. permitted 호출이 느리면
     *   HALF_OPEN 상태에서 빠져나오지 못해 새 요청이 모두 거절된다.
     */
    @Test
    void maxWaitDurationInHalfOpenState_기본값_0이면_무한_대기() throws Exception {
        CircuitBreaker cb = openCircuit(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .slidingWindowSize(5)
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // maxWaitDurationInHalfOpenState 기본값 = 0 (무한)
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());

        // SLOW 5s → permitted 호출이 오래 걸림
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "5000", "slowMaxMs", "5000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 10000);

        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 1건 SLOW 호출 시작 (5s 소요)
        ExecutorService executor = Executors.newFixedThreadPool(1);
        Future<?> slowCall = executor.submit(() -> {
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm("pk_ho_slow", "order_ho_slow", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        });

        Thread.sleep(500); // 호출이 진행 중

        // 3초 후에도 여전히 HALF_OPEN (maxWait=0 → 무한 대기)
        Thread.sleep(2500);
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        try { slowCall.get(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
        executor.shutdown();
    }

    /**
     * maxWaitDurationInHalfOpenState를 설정하면 시간 초과 시 OPEN으로 복귀하는 것을 검증한다.
     *
     * 흐름:
     *   OPEN → 수동 HALF_OPEN 전환 → SLOW(10s) 호출 3건 시작
     *   → maxWaitDuration(2s) 초과 → 강제 OPEN 복귀
     *
     * 해결:
     *   maxWaitDurationInHalfOpenState를 설정하면 permitted 호출이 느려도
     *   지정 시간 후 강제로 OPEN으로 복귀하여 무한 대기를 방지한다.
     */
    @Test
    void maxWaitDurationInHalfOpenState_설정시_시간_초과하면_OPEN_복귀() throws Exception {
        // waitDurationInOpenState를 길게 설정: maxWait→OPEN 후 재전환 방지
        CircuitBreaker cb = openCircuit(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .slidingWindowSize(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .maxWaitDurationInHalfOpenState(Duration.ofSeconds(2))
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());

        // SLOW 10s → permitted 호출이 매우 오래 걸림
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "10000", "slowMaxMs", "10000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 15000);

        // 수동으로 HALF_OPEN 전환 (30s 대기 불필요)
        cb.transitionToHalfOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 3건 SLOW 호출 시작 (10s 소요 → maxWait 2s 초과)
        ExecutorService executor = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            executor.submit(() -> {
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm("pk_ho_mw_" + idx, "order_ho_mw", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            });
        }

        // maxWaitDuration(2s) 후 강제 OPEN 복귀
        // waitDuration=30s이므로 다시 HALF_OPEN 되지 않음
        Thread.sleep(3000);
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        executor.shutdownNow();
    }
}
