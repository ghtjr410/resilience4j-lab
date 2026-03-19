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
class SlowCallDetectionTest extends ExampleTestBase {

    /**
     * 모든 호출이 느릴 때 slowCallRate로 서킷이 OPEN되는 기본 동작을 검증한다.
     *
     * 흐름:
     *   SLOW 2~2.5s 설정 + threshold 1s → 전부 slowCall
     *   → slowCallRate 100% > slowCallRateThreshold(50%) → OPEN
     *
     * 핵심:
     *   slowCall 감지는 failureRate와 별도로 동작한다.
     *   failureRateThreshold를 100%로 설정해도 slowCallRate로 서킷이 열릴 수 있다.
     */
    @Test
    void SLOW_전부이면_slowCallRate_100퍼센트로_OPEN() {
        // SLOW 2~2.5s, threshold 1s → 전부 slowCall
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2500"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 5000); // readTimeout=5s (SLOW보다 넉넉하게)

        CircuitBreaker cb = CircuitBreaker.of("slow-basic-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(100)  // 일반 실패로는 안 열리게
                        .slowCallDurationThreshold(Duration.ofSeconds(1))
                        .slowCallRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        for (int i = 0; i < 5; i++) {
            String key = "pk_slow_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_slow", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }

        // slowCallRate 100% > 50% → OPEN
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.getMetrics().getSlowCallRate()).isGreaterThanOrEqualTo(50.0f);
    }

    /**
     * slowCall은 요청을 끊지 않으며, 느린 성공도 장애의 전조로 집계되는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 2s + threshold 1s + readTimeout 5s → 3건 모두 느리지만 정상 응답
     *   → 사용자에게는 성공이지만 서킷에는 slowCall로 집계됨
     *   → slowCallRate 100% > 80% → OPEN
     *
     * 핵심:
     *   slowCall은 timeout과 다르게 요청을 끊지 않는다.
     *   응답을 정상적으로 받으면서도 "이 서버는 느려지고 있다"는 신호를 집계한다.
     *   느린 성공이 반복되면 곧 타임아웃이 발생할 전조이므로 미리 서킷을 연다.
     */
    @Test
    void slowCall은_요청을_끊지_않고_느린_성공도_장애_전조로_집계한다() {
        // SLOW 2s, threshold 1s, readTimeout 5s
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 5000);

        CircuitBreaker cb = CircuitBreaker.of("slow-success-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(100)
                        .slowCallDurationThreshold(Duration.ofSeconds(1))
                        .slowCallRateThreshold(80)
                        .minimumNumberOfCalls(3)
                        .slidingWindowSize(3)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // 3건 모두 느리지만 성공
        for (int i = 0; i < 3; i++) {
            String key = "pk_slow_ok_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_slow_ok", 10000));
            Map<String, Object> result = decorated.get();
            // 응답을 정상적으로 받음 (끊기지 않음)
            assertThat(result).isNotNull();
        }

        // 사용자에게는 성공이지만 서킷에는 slowCall로 집계됨
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfSlowSuccessfulCalls()).isEqualTo(3);
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(3);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        // slowCallRate 100% > 80% → OPEN
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * slowCall과 failure가 복합적으로 발생할 때, 둘 중 하나라도 threshold를 넘으면 OPEN이 되는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 2s → 모든 호출이 느림 + 일부는 에러 트리거로 500 응답
     *   → failureRate: 50% (2/4) < 80% → 이것만으로는 안 열림
     *   → slowCallRate: 100% (4/4) > 50% → 이걸로 열림
     *
     * 핵심:
     *   failureRate와 slowCallRate는 독립적으로 평가된다.
     *   둘 중 하나라도 threshold를 넘으면 서킷이 열린다.
     */
    @Test
    void slowCall과_failure_복합시_둘_중_하나라도_threshold_넘으면_OPEN() {
        // SLOW 2s → 모든 호출이 느림. 에러 트리거로 일부 실패 추가.
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 5000);

        CircuitBreaker cb = CircuitBreaker.of("slow-composite-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(80)    // 실패율로는 안 열리게 (높은 threshold)
                        .slowCallDurationThreshold(Duration.ofSeconds(1))
                        .slowCallRateThreshold(50)    // slowCallRate로 열림
                        .minimumNumberOfCalls(4)
                        .slidingWindowSize(4)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // 2건 정상 (느린 성공) + 2건 에러 트리거 (느린 실패)
        for (int i = 0; i < 2; i++) {
            String key = "pk_sc_ok_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_sc_ok", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        for (int i = 0; i < 2; i++) {
            String key = "pk_sc_err_" + i;
            // system_error 트리거 → 500 (느린 실패)
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "system_error", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }

        // failureRate: 50% (2/4) < 80% → 이것만으로는 안 열림
        // slowCallRate: 100% (4/4) > 50% → 이걸로 열림
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfSlowCalls()).isEqualTo(4);
        assertThat(cb.getMetrics().getNumberOfSlowFailedCalls()).isEqualTo(2);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * HALF_OPEN에서 느린 성공만으로도 slowCallRate 초과 시 OPEN으로 재진입하는 것을 검증한다.
     *
     * 흐름:
     *   1. SLOW 모드 → slowCallRate로 OPEN
     *   2. SLOW 유지 + 대기 → HALF_OPEN
     *   3. 3건 모두 느리지만 성공 → slowCallRate 100% > 50% → OPEN 재진입
     *
     * 핵심:
     *   성공해도 느리면 복구가 안 된다.
     *   HALF_OPEN에서도 slowCallRate가 평가되며, threshold를 넘으면 다시 OPEN된다.
     */
    @Test
    void HALF_OPEN에서_느린_성공만으로도_slowCallRate_초과시_OPEN_재진입한다() throws InterruptedException {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 5000);

        CircuitBreaker cb = CircuitBreaker.of("slow-ho-reopen-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(100)  // 일반 실패로는 안 열리게
                        .slowCallDurationThreshold(Duration.ofSeconds(1))
                        .slowCallRateThreshold(50)
                        .minimumNumberOfCalls(3)
                        .slidingWindowSize(3)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // Phase 1: SLOW → slowCallRate 100% → OPEN
        for (int i = 0; i < 3; i++) {
            String key = "pk_slow_ho_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_slow_ho", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Phase 2: 대기 → HALF_OPEN (SLOW 유지)
        Thread.sleep(1500);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Phase 3: 3건 느린 성공 → slowCallRate 100% > 50% → OPEN 재진입
        for (int i = 0; i < 3; i++) {
            String key = "pk_slow_ho_re_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_slow_ho_re", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.getMetrics().getSlowCallRate()).isGreaterThanOrEqualTo(50.0f);
    }
}
