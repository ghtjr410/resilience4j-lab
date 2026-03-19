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

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SpecialStateTest extends ExampleTestBase {

    /**
     * FORCED_OPEN 상태에서는 모든 요청이 거부되는 것을 검증한다.
     *
     * 흐름:
     *   1. transitionToForcedOpenState() 호출
     *   2. 정상 요청 → CallNotPermittedException
     *
     * 운영 시나리오:
     *   PG사 점검 시 수동으로 서킷을 내려 트래픽을 차단할 수 있다.
     *   failureRate와 무관하게 즉시 모든 요청을 거부한다.
     */
    @Test
    void FORCED_OPEN_상태에서는_모든_요청이_거부된다() {
        paymentClient.setChaosMode("NORMAL");

        CircuitBreaker cb = CircuitBreaker.of("forced-open-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // 강제 OPEN
        cb.transitionToForcedOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);

        // 모든 요청이 거부됨
        for (int i = 0; i < 3; i++) {
            String key = "pk_forced_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_forced", 10000));
            assertThatThrownBy(decorated::get)
                    .isInstanceOf(CallNotPermittedException.class);
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
    }

    /**
     * DISABLED 상태에서는 서킷이 항상 허용하고 상태 전이가 없는 것을 검증한다.
     *
     * 흐름:
     *   1. transitionToDisabledState() 호출
     *   2. DEAD 모드에서 5건 실패 → 차단 없음, DISABLED 유지
     *
     * 운영 시나리오:
     *   서킷브레이커를 일시적으로 비활성화해야 할 때 사용한다.
     *   모든 호출을 통과시키며, 메트릭도 수집하지 않는다.
     */
    @Test
    void DISABLED_상태에서는_서킷이_항상_허용하고_상태_전이가_없다() {
        paymentClient.setChaosMode("DEAD");

        CircuitBreaker cb = CircuitBreaker.of("disabled-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // DISABLED로 전환
        cb.transitionToDisabledState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.DISABLED);

        // 5건 실패해도 차단 없음
        for (int i = 0; i < 5; i++) {
            String key = "pk_disabled_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_disabled", 10000));
            try { decorated.get(); } catch (CallNotPermittedException e) {
                throw new AssertionError("DISABLED 상태에서 요청이 차단되면 안 된다", e);
            } catch (Exception ignored) {
                // 서버 에러는 예상됨 (DEAD 모드)
            }
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.DISABLED);
    }

    /**
     * METRICS_ONLY 상태에서는 메트릭만 수집하고 차단하지 않는 것을 검증한다.
     *
     * 흐름:
     *   1. transitionToMetricsOnlyState() 호출
     *   2. DEAD 모드에서 5건 실패 → 실패 집계는 되지만 차단 없음
     *
     * 운영 시나리오:
     *   새 서킷브레이커 설정을 프로덕션에서 검증할 때 사용한다.
     *   차단 없이 메트릭만 수집해서 threshold가 적절한지 모니터링할 수 있다.
     */
    @Test
    void METRICS_ONLY_상태에서는_메트릭만_수집하고_차단하지_않는다() {
        paymentClient.setChaosMode("DEAD");

        CircuitBreaker cb = CircuitBreaker.of("metrics-only-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // METRICS_ONLY로 전환
        cb.transitionToMetricsOnlyState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.METRICS_ONLY);

        // 5건 실패 — 차단 없이 통과
        for (int i = 0; i < 5; i++) {
            String key = "pk_metrics_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_metrics", 10000));
            try { decorated.get(); } catch (CallNotPermittedException e) {
                throw new AssertionError("METRICS_ONLY 상태에서 요청이 차단되면 안 된다", e);
            } catch (Exception ignored) {
                // 서버 에러는 예상됨 (DEAD 모드)
            }
        }

        // 메트릭은 수집됨
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.METRICS_ONLY);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
    }
}
