package com.example.resilience.circuitbreaker;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WaitIntervalFunctionTest extends ExampleTestBase {

    private void triggerOpen(CircuitBreaker cb) {
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 5; i++) {
            String key = "pk_wif_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_wif", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * waitIntervalFunctionInOpenState로 exponential backoff를 적용하면
     * OPEN→HALF_OPEN 전이 대기 시간이 실패 반복마다 증가하는 것을 검증한다.
     *
     * 흐름:
     *   1차 OPEN: 1초 대기 → HALF_OPEN → 실패 → OPEN
     *   2차 OPEN: 2초 대기 → HALF_OPEN → 성공 → CLOSED
     *
     * 핵심:
     *   기본 waitDurationInOpenState는 고정값이다.
     *   waitIntervalFunctionInOpenState(IntervalFunction.ofExponentialBackoff)를 사용하면
     *   HALF_OPEN에서 다시 OPEN이 될 때마다 대기 시간이 지수적으로 증가한다.
     *
     *   Retry의 exponentialBackoff와 대응되는 CB 측 기능이다:
     *   - Retry: 재시도 간격을 점진적으로 증가
     *   - CB: OPEN 유지 시간을 점진적으로 증가
     *   둘 다 "계속 실패하면 더 오래 쉬어라"라는 같은 철학이다.
     */
    @Test
    void exponential_backoff로_OPEN_대기_시간이_실패_반복마다_증가한다() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.of("wif-exp-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        // 1초 초기값, 2배 증가 → 1s, 2s, 4s, ...
                        .waitIntervalFunctionInOpenState(
                                IntervalFunction.ofExponentialBackoff(1000, 2))
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // === 1차 OPEN (대기시간 ~1초) ===
        triggerOpen(cb);

        // 1.5초 후 HALF_OPEN 전환 확인
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // HALF_OPEN에서 다시 실패 → OPEN 재진입
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 3; i++) {
            String key = "pk_wif_ho1_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_wif_ho1", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // === 2차 OPEN (대기시간 ~2초) ===

        // 1.5초 후: 아직 OPEN (2차 대기시간은 ~2초이므로)
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 1초 더 대기 (총 ~2.5초) → HALF_OPEN 전환
        Thread.sleep(1000);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 성공 → CLOSED 복구
        paymentClient.setChaosMode("NORMAL");
        for (int i = 0; i < 3; i++) {
            String key = "pk_wif_ho2_ok_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_wif_ho2_ok", 10000));
            decorated.get();
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * 고정 waitDuration과 exponential backoff의 동작 차이를 검증한다.
     *
     * 흐름:
     *   고정: 1차 OPEN 1초, 2차 OPEN 1초 (동일)
     *   backoff: 1차 OPEN 1초, 2차 OPEN 2초 (증가)
     *
     * 핵심:
     *   고정 waitDuration은 장애가 반복되어도 같은 간격으로 HALF_OPEN을 시도한다.
     *   exponential backoff는 반복 실패 시 간격을 늘려 불필요한 탐색 호출을 줄인다.
     *   장시간 장애 상황에서 서버에 가는 탐색 부하를 줄이는 효과가 있다.
     */
    @Test
    void 고정_waitDuration은_실패_반복해도_대기_시간이_동일하다() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.of("wif-fixed-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .waitDurationInOpenState(Duration.ofSeconds(1)) // 고정 1초
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // 1차 OPEN
        triggerOpen(cb);
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // HALF_OPEN → 실패 → 2차 OPEN
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 3; i++) {
            String key = "pk_wif_fix_ho_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_wif_fix_ho", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 2차도 동일하게 1.5초 후 HALF_OPEN (고정이므로 증가하지 않음)
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }
}
