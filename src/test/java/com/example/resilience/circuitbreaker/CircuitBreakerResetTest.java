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

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CircuitBreakerResetTest extends ExampleTestBase {

    /**
     * OPEN 상태에서 reset() 호출 시 CLOSED로 복구되고 메트릭이 초기화되는 것을 검증한다.
     *
     * 흐름:
     *   1. DEAD → 5건 실패 → OPEN 전환
     *   2. reset() 호출 → CLOSED + 메트릭 초기화
     *   3. 초기화 확인: 실패 0건, 성공 0건, 실패율 -1 (미평가)
     *
     * 핵심:
     *   reset()은 운영 중 긴급 복구용 API이다.
     *   waitDuration을 기다리지 않고 즉시 서킷을 CLOSED로 되돌릴 수 있다.
     *   장애가 해소된 것을 운영자가 확인한 후 수동으로 복구할 때 사용한다.
     */
    @Test
    void OPEN에서_reset_호출시_CLOSED로_복구되고_메트릭이_초기화된다() {
        paymentClient.setChaosMode("DEAD");

        CircuitBreaker cb = CircuitBreaker.of("reset-open-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // 5건 실패 → OPEN
        for (int i = 0; i < 5; i++) {
            String key = "pk_reset_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_reset", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);

        // reset() → CLOSED + 메트릭 초기화
        cb.reset();

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(0);
        assertThat(cb.getMetrics().getFailureRate()).isEqualTo(-1.0f); // 미평가 상태
    }

    /**
     * FORCED_OPEN에서 reset() 호출 시 CLOSED로 복구되는 것을 검증한다.
     *
     * 흐름:
     *   1. transitionToForcedOpenState() → FORCED_OPEN
     *   2. reset() → CLOSED
     *   3. 정상 요청 성공 확인
     *
     * 핵심:
     *   PG사 점검 시 FORCED_OPEN으로 트래픽을 차단했다가,
     *   점검이 끝나면 reset()으로 즉시 복구할 수 있다.
     *   transitionToClosedState()와 달리 reset()은 메트릭도 함께 초기화한다.
     */
    @Test
    void FORCED_OPEN에서_reset_호출시_CLOSED로_복구되고_정상_요청이_통과한다() {
        paymentClient.setChaosMode("NORMAL");

        CircuitBreaker cb = CircuitBreaker.of("reset-forced-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // FORCED_OPEN → 모든 요청 거절
        cb.transitionToForcedOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);

        // reset() → CLOSED
        cb.reset();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 정상 요청 통과
        Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                () -> paymentClient.confirm("pk_reset_ok", "order_reset_ok", 10000));
        Map<String, Object> result = decorated.get();
        assertThat(result).isNotNull();

        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    /**
     * reset() 후 이전 실패 이력이 이월되지 않는 것을 검증한다.
     *
     * 흐름:
     *   1. DEAD → 5건 실패 → OPEN
     *   2. reset() → CLOSED (메트릭 초기화)
     *   3. NORMAL → 2건 실패 + 3건 성공 = 40% < 50% → CLOSED 유지
     *
     * 핵심:
     *   reset()은 sliding window까지 완전히 초기화한다.
     *   RecoveryTest의 "CLOSED_복구_후_슬라이딩_윈도우가_초기화"와 달리,
     *   HALF_OPEN을 거치지 않고 즉시 초기화할 수 있다.
     */
    @Test
    void reset_후_이전_실패_이력이_이월되지_않는다() {
        paymentClient.setChaosMode("DEAD");

        CircuitBreaker cb = CircuitBreaker.of("reset-clean-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // 5건 실패 → OPEN
        for (int i = 0; i < 5; i++) {
            String key = "pk_reset_cl_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_reset_cl", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // reset() → CLOSED
        cb.reset();

        // 2건 실패 + 3건 성공 = 40% < 50% → CLOSED 유지
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 2; i++) {
            String key = "pk_reset_cl_f_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_reset_cl_f", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        paymentClient.setChaosMode("NORMAL");
        for (int i = 0; i < 3; i++) {
            String key = "pk_reset_cl_s_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_reset_cl_s", 10000));
            decorated.get();
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getFailureRate()).isLessThan(50.0f);
    }
}
