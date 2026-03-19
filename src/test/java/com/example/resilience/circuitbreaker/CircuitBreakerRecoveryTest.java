package com.example.resilience.circuitbreaker;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
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
class CircuitBreakerRecoveryTest extends ExampleTestBase {

    /**
     * HALF_OPEN 상태에서 성공 응답을 받으면 CLOSED로 복구되는지 검증한다.
     *
     * 흐름:
     *   1. DEAD 모드 → 5건 실패 → OPEN 전환
     *   2. NORMAL 모드로 전환 + waitDuration(1s) 대기 → HALF_OPEN 자동 전환
     *   3. HALF_OPEN에서 permittedNumberOfCalls(3)건 성공 → CLOSED 복구
     *
     * 핵심:
     *   서킷 복구의 핵심 경로. 장애가 해소되면 HALF_OPEN을 거쳐 정상으로 돌아온다.
     */
    @Test
    void HALF_OPEN에서_성공하면_CLOSED로_복구된다() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.of("recovery-success-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // DEAD → OPEN
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 5; i++) {
            String key = "pk_rec_s_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_rec", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // NORMAL로 전환 + 대기 → HALF_OPEN
        paymentClient.setChaosMode("NORMAL");
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // HALF_OPEN에서 3건 성공 → CLOSED
        for (int i = 0; i < 3; i++) {
            String key = "pk_rec_s_ok_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_rec_ok", 10000));
            decorated.get();
        }
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * HALF_OPEN 상태에서 다시 실패하면 OPEN으로 재진입하는지 검증한다.
     *
     * 흐름:
     *   1. DEAD 모드 → 5건 실패 → OPEN 전환
     *   2. waitDuration(1s) 대기 → HALF_OPEN 자동 전환 (DEAD 유지)
     *   3. HALF_OPEN에서 3건 실패 → OPEN 재진입
     *
     * 핵심:
     *   장애가 계속되면 HALF_OPEN에서 다시 OPEN으로 돌아간다.
     *   서킷이 장애 서버에 트래픽을 보내지 않도록 보호하는 메커니즘이다.
     */
    @Test
    void HALF_OPEN에서_실패하면_OPEN으로_재진입한다() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.of("recovery-fail-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // DEAD → OPEN
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 5; i++) {
            String key = "pk_rec_f_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_rec", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 대기 → HALF_OPEN (DEAD 유지)
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // HALF_OPEN에서 실패 → OPEN 재진입
        for (int i = 0; i < 3; i++) {
            String key = "pk_rec_f_fail_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_rec_fail", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * CLOSED로 복구된 후 sliding window가 초기화되어, 이전 실패 이력이 이월되지 않는 것을 검증한다.
     *
     * 흐름:
     *   1. DEAD → 5건 실패 → OPEN
     *   2. NORMAL + 대기 → HALF_OPEN → 3건 성공 → CLOSED 복구
     *   3. 복구 후: 2건 실패 + 3건 성공 = 40% < 50% → CLOSED 유지
     *
     * 핵심:
     *   CLOSED 복구 시 sliding window가 리셋된다.
     *   리셋이 안 됐다면 이전 실패 이력 때문에 바로 다시 OPEN이 될 것이다.
     */
    @Test
    void CLOSED_복구_후_슬라이딩_윈도우가_초기화되어_이전_실패가_이월되지_않는다() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.of("recovery-reset-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // Phase 1: DEAD → 5건 실패 → OPEN
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 5; i++) {
            String key = "pk_reset_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_reset", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Phase 2: NORMAL + 대기 → HALF_OPEN → 3건 성공 → CLOSED
        paymentClient.setChaosMode("NORMAL");
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        for (int i = 0; i < 3; i++) {
            String key = "pk_reset_ok_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_reset_ok", 10000));
            decorated.get();
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Phase 3: 복구 후 2건 실패 + 3건 성공 = 40% < 50% → CLOSED 유지
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 2; i++) {
            String key = "pk_reset_f2_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_reset_f2", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        paymentClient.setChaosMode("NORMAL");
        for (int i = 0; i < 3; i++) {
            String key = "pk_reset_s2_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_reset_s2", 10000));
            decorated.get();
        }

        // 윈도우가 리셋됐으므로 40% < 50% → CLOSED 유지
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getFailureRate()).isLessThan(50.0f);
    }

    /**
     * HALF_OPEN에서 실패율이 threshold 미만이면 CLOSED로 복구되는 것을 검증한다.
     *
     * 흐름:
     *   1. DEAD → 5건 실패 → OPEN
     *   2. 대기 → HALF_OPEN
     *   3. permittedCalls=3 중: system_error 1건 실패 + 2건 성공 = 33% < 50% → CLOSED
     *
     * 핵심:
     *   HALF_OPEN에서 모두 성공할 필요는 없다. threshold 미만이면 복구된다.
     */
    @Test
    void HALF_OPEN에서_실패율이_threshold_미만이면_CLOSED로_복구된다() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.of("ho-boundary-close-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(3)
                        .slidingWindowSize(5)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // DEAD → OPEN
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 5; i++) {
            String key = "pk_ho_bc_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_ho_bc", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 대기 → HALF_OPEN
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 1건 실패 (system_error 트리거) + 2건 성공 = 33% < 50% → CLOSED
        paymentClient.setChaosMode("NORMAL");
        String failKey = "pk_ho_bc_fail";
        Supplier<Map<String, Object>> failDecorated = CircuitBreaker.decorateSupplier(cb,
                () -> paymentClient.confirm(failKey, "system_error", 10000));
        try { failDecorated.get(); } catch (Exception ignored) {}

        for (int i = 0; i < 2; i++) {
            String key = "pk_ho_bc_ok_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_ho_bc_ok", 10000));
            decorated.get();
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * HALF_OPEN에서 실패율이 threshold 이상이면 OPEN으로 재진입하는 것을 검증한다.
     *
     * 흐름:
     *   1. DEAD → 5건 실패 → OPEN
     *   2. 대기 → HALF_OPEN
     *   3. permittedCalls=3 중: system_error 2건 실패 + 1건 성공 = 66% > 50% → OPEN
     *
     * 핵심:
     *   기존 테스트는 HALF_OPEN에서 100% 실패만 검증했다.
     *   이 테스트는 threshold 경계값(66% > 50%)에서도 OPEN 재진입이 되는지 확인한다.
     */
    @Test
    void HALF_OPEN에서_실패율이_threshold_이상이면_OPEN으로_재진입한다() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.of("ho-boundary-open-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(3)
                        .slidingWindowSize(5)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // DEAD → OPEN
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 5; i++) {
            String key = "pk_ho_bo_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_ho_bo", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 대기 → HALF_OPEN
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 2건 실패 (system_error) + 1건 성공 = 66% > 50% → OPEN
        paymentClient.setChaosMode("NORMAL");
        for (int i = 0; i < 2; i++) {
            String failKey = "pk_ho_bo_fail_" + i;
            Supplier<Map<String, Object>> failDecorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(failKey, "system_error", 10000));
            try { failDecorated.get(); } catch (Exception ignored) {}
        }

        String okKey = "pk_ho_bo_ok";
        Supplier<Map<String, Object>> okDecorated = CircuitBreaker.decorateSupplier(cb,
                () -> paymentClient.confirm(okKey, "order_ho_bo_ok", 10000));
        try { okDecorated.get(); } catch (Exception ignored) {}

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
