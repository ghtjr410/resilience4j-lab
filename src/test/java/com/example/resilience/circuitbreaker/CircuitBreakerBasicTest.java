package com.example.resilience.circuitbreaker;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CircuitBreakerBasicTest extends ExampleTestBase {

    private CircuitBreaker createCircuitBreaker() {
        return CircuitBreaker.of("test-" + UUID.randomUUID(), CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(10)
                .slidingWindowSize(10)
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
    }

    /**
     * DEAD 모드에서 모든 요청이 실패하면 서킷이 OPEN으로 전환되는지 검증한다.
     *
     * 흐름:
     *   DEAD 모드 설정 → 10건 요청 전부 실패 → 실패율 100%
     *   → failureRateThreshold(50%) 초과 → OPEN 전환
     *   → 11번째 요청은 서킷이 열려있으므로 CallNotPermittedException 발생
     */
    @Test
    void DEAD_모드에서_모든_요청_실패시_서킷이_OPEN으로_전환된다() {
        paymentClient.setChaosMode("DEAD");
        CircuitBreaker cb = createCircuitBreaker();
        TestLogger.attach(cb);

        // 10건 실패 → 실패율 100% → OPEN
        for (int i = 0; i < 10; i++) {
            String key = "pk_dead_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_dead", 10000));
            try {
                decorated.get();
            } catch (HttpServerErrorException | ResourceAccessException ignored) {
            }
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 11번째는 CallNotPermittedException
        Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                () -> paymentClient.confirm("pk_dead_11", "order_dead", 10000));
        assertThatThrownBy(decorated::get).isInstanceOf(CallNotPermittedException.class);
    }

    /**
     * 부분 장애율 30%일 때 서킷이 CLOSED를 유지하는지 검증한다.
     *
     * 흐름:
     *   PARTIAL_FAILURE 30% 설정 → slidingWindowSize=100 → 100건 요청 → 약 30% 실패
     *   → failureRateThreshold(50%) 미만 → CLOSED 유지
     *
     * 핵심:
     *   실패율이 threshold 미만이면 서킷은 열리지 않는다.
     *
     * 설계:
     *   확률 기반 테스트에서는 통계적 마진이 충분해야 한다.
     *   slidingWindowSize=100이면 실패율 분산이 작아져(30%±~4.6%)
     *   50% threshold를 우연히 넘을 확률이 사실상 0이다.
     */
    @Test
    void PARTIAL_FAILURE_30퍼센트이면_실패율_50퍼센트_미만으로_CLOSED_유지() {
        paymentClient.setChaosMode("PARTIAL_FAILURE", Map.of("partialFailureRate", "30"));

        CircuitBreaker cb = CircuitBreaker.of("pf30-" + UUID.randomUUID(), CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(100)
                .slidingWindowSize(100) // 윈도우를 크게 → 확률적 편차 억제
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(cb);

        for (int i = 0; i < 100; i++) {
            String key = "pk_pf30_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_pf30", 10000));
            try {
                decorated.get();
            } catch (HttpServerErrorException | HttpClientErrorException | ResourceAccessException ignored) {
            }
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * 부분 장애율 90%일 때 서킷이 OPEN으로 전환되는지 검증한다.
     *
     * 흐름:
     *   PARTIAL_FAILURE 90% 설정 → slidingWindowSize=100 → 100건 요청 → 약 90% 실패
     *   → failureRateThreshold(50%) 초과 → OPEN 전환
     *
     * 주의:
     *   PARTIAL_FAILURE는 4xx/5xx를 혼합 반환하므로 HttpClientErrorException도
     *   recordExceptions에 포함해야 정확한 장애율을 집계할 수 있다.
     *
     * 설계:
     *   slidingWindowSize=100이면 90% 실패율의 분산이 작아져(90%±~3%)
     *   50% threshold를 못 넘을 확률이 사실상 0이다.
     */
    @Test
    void PARTIAL_FAILURE_90퍼센트이면_실패율_50퍼센트_초과로_OPEN_전환() {
        paymentClient.setChaosMode("PARTIAL_FAILURE", Map.of("partialFailureRate", "90"));

        // PARTIAL_FAILURE는 4xx/5xx 혼합 반환 → 둘 다 recordExceptions에 포함해야 정확한 장애율 집계
        CircuitBreaker cb = CircuitBreaker.of("pf90-" + UUID.randomUUID(), CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(100)
                .slidingWindowSize(100) // 윈도우를 크게 → 확률적 편차 억제
                .recordExceptions(HttpServerErrorException.class, HttpClientErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(cb);

        for (int i = 0; i < 100; i++) {
            String key = "pk_pf90_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_pf90", 10000));
            try {
                decorated.get();
            } catch (HttpServerErrorException | HttpClientErrorException | ResourceAccessException | CallNotPermittedException ignored) {
            }
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * recordResult로 예외 없는 응답도 실패로 집계할 수 있는 것을 검증한다.
     *
     * 흐름:
     *   NORMAL 모드 + recordResult(status=="IN_PROGRESS")
     *   → 5건 중 5건이 IN_PROGRESS 결과 → 예외 없이 실패율 100% → OPEN
     *
     * 핵심:
     *   PG API가 HTTP 200 + status="IN_PROGRESS"를 반환하면 예외가 안 나온다.
     *   recordExceptions로는 이걸 실패로 집계할 수 없다.
     *   recordResult를 사용하면 응답 본문의 비즈니스 상태를 실패로 판정할 수 있다.
     *
     *   Retry의 retryOnResult와 대응되는 CB 측 기능이다.
     *   Retry는 "재시도 여부"를 결과로 판단하고, CB는 "실패 집계 여부"를 결과로 판단한다.
     */
    @Test
    @SuppressWarnings("unchecked")
    void recordResult로_예외_없는_응답도_실패로_집계할_수_있다() {
        paymentClient.setChaosMode("NORMAL");
        AtomicInteger callCount = new AtomicInteger(0);

        CircuitBreaker cb = CircuitBreaker.of("record-result-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordResult(result -> {
                            if (result instanceof Map) {
                                return "IN_PROGRESS".equals(((Map<String, Object>) result).get("status"));
                            }
                            return false;
                        })
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // 5건 모두 IN_PROGRESS 반환 → 예외 없이 실패율 100%
        for (int i = 0; i < 5; i++) {
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb, () -> {
                callCount.incrementAndGet();
                return Map.of("status", "IN_PROGRESS", "paymentKey", "pk_in_progress");
            });
            try {
                decorated.get();
            } catch (Exception ignored) {}
        }

        // 예외가 없었는데도 실패율 100% → OPEN
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
        assertThat(callCount.get()).isEqualTo(5);
    }

    /**
     * recordResult 조건에 맞지 않는 정상 응답은 성공으로 집계되는 것을 검증한다.
     *
     * 흐름:
     *   recordResult(status=="IN_PROGRESS") 설정
     *   → status="DONE" 응답 5건 → predicate 불일치 → 성공 5건 → CLOSED 유지
     *
     * 핵심:
     *   recordResult는 predicate가 true일 때만 실패로 집계한다.
     *   정상 응답(DONE)은 predicate 불일치 → 성공으로 처리된다.
     */
    @Test
    @SuppressWarnings("unchecked")
    void recordResult_조건_불일치시_성공으로_집계된다() {
        paymentClient.setChaosMode("NORMAL");

        CircuitBreaker cb = CircuitBreaker.of("record-result-ok-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordResult(result -> {
                            if (result instanceof Map) {
                                return "IN_PROGRESS".equals(((Map<String, Object>) result).get("status"));
                            }
                            return false;
                        })
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // 5건 정상 응답 (status=DONE) → predicate 불일치 → 성공
        for (int i = 0; i < 5; i++) {
            String key = "pk_done_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_done", 10000));
            decorated.get();
        }

        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(5);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
    }
}
