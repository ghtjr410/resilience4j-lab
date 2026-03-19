package com.example.resilience.circuitbreaker;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
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
class TimeBasedWindowTest extends ExampleTestBase {

    /**
     * TIME_BASED 윈도우에서 시간이 경과하면 이전 실패가 만료되는 것을 검증한다.
     *
     * 흐름:
     *   1. TIME_BASED(3초 윈도우) + system_error로 실패 → OPEN
     *   2. NORMAL + 대기 → HALF_OPEN → 성공 → CLOSED 복구
     *   3. 4초 대기 (윈도우 만료)
     *   4. 1건 실패 + 2건 성공 = 33% < 50% → CLOSED 유지
     *
     * 핵심:
     *   COUNT_BASED와의 차이: TIME_BASED는 시간이 지나면 오래된 호출이 자동으로 빠진다.
     *   COUNT_BASED는 최근 N건을 유지하지만, TIME_BASED는 최근 N초 내 호출만 유지한다.
     */
    @Test
    void TIME_BASED_윈도우에서_시간_경과_후_이전_실패가_만료된다() throws InterruptedException {
        CircuitBreaker cb = CircuitBreaker.of("time-based-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .slidingWindowType(SlidingWindowType.TIME_BASED)
                        .slidingWindowSize(3)  // 3초 윈도우
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(3)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // Phase 1: 실패 → OPEN
        paymentClient.setChaosMode("DEAD");
        for (int i = 0; i < 3; i++) {
            String key = "pk_tb_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_tb", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Phase 2: NORMAL + 대기 → HALF_OPEN → 성공 → CLOSED
        paymentClient.setChaosMode("NORMAL");
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        for (int i = 0; i < 3; i++) {
            String key = "pk_tb_ok_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_tb_ok", 10000));
            decorated.get();
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Phase 3: 4초 대기 → 윈도우 만료
        Thread.sleep(4000);

        // Phase 4: 1건 실패 + 2건 성공 = 33% < 50% → CLOSED 유지
        String failKey = "pk_tb_after_fail";
        Supplier<Map<String, Object>> failDecorated = CircuitBreaker.decorateSupplier(cb,
                () -> paymentClient.confirm(failKey, "system_error", 10000));
        try { failDecorated.get(); } catch (Exception ignored) {}

        for (int i = 0; i < 2; i++) {
            String key = "pk_tb_after_ok_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_tb_after", 10000));
            decorated.get();
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getFailureRate()).isLessThan(50.0f);
    }
}
