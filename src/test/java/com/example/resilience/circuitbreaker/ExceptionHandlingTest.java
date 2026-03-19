package com.example.resilience.circuitbreaker;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ExceptionHandlingTest extends ExampleTestBase {

    /**
     * 기본 동작에서 모든 예외가 실패로 집계되어 비즈니스 에러도 서킷 오진을 유발하는 것을 검증한다.
     *
     * 흐름:
     *   recordExceptions/ignoreExceptions 미설정 → 모든 예외가 실패로 집계
     *   → reject_company(403) 5건 → 실패율 100% → OPEN
     *
     * 문제:
     *   기본 설정에서는 비즈니스 에러(4xx)도 장애로 판단하여 서킷이 열린다.
     *   이는 실제 서버 장애가 아님에도 서킷을 여는 오진이다.
     */
    @Test
    void 기본_동작에서_모든_예외가_실패로_집계되어_비즈니스_에러도_서킷_오진() {
        paymentClient.setChaosMode("NORMAL");

        // recordExceptions/ignoreExceptions 미설정 → 모든 예외가 실패
        CircuitBreaker cb = CircuitBreaker.of("exc-default-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .build());
        TestLogger.attach(cb);

        // reject_company → 403 비즈니스 에러
        for (int i = 0; i < 5; i++) {
            String key = "pk_exc_def_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "reject_company", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }

        // 비즈니스 에러(403)가 실패로 집계 → OPEN (오진!)
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * ignoreExceptions를 설정하면 특정 예외가 서킷 집계에서 무시되는 것을 검증한다.
     *
     * 흐름:
     *   ignoreExceptions(HttpClientErrorException) 설정
     *   → 403 에러 10건 → 서킷 CLOSED (무시됨, failedCalls=0)
     *   → 500 에러 5건 → 실패로 집계됨 (recordExceptions에 포함)
     *
     * 핵심:
     *   ignore된 예외는 성공도 실패도 아닌 "무시"로 처리되어 집계에서 완전히 제외된다.
     */
    @Test
    void ignoreExceptions_설정시_특정_예외가_집계에서_무시된다() {
        paymentClient.setChaosMode("NORMAL");

        CircuitBreaker cb = CircuitBreaker.of("exc-ignore-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(10)
                        .ignoreExceptions(HttpClientErrorException.class)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // 403 에러 10건 → 서킷 CLOSED (ignoreExceptions로 무시됨)
        for (int i = 0; i < 10; i++) {
            String key = "pk_exc_ign_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "reject_company", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        // ignore된 호출은 실패 카운트에 들어가지 않음
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);

        // 500 에러 5건 → 서킷 OPEN (recordExceptions에 포함)
        for (int i = 0; i < 5; i++) {
            String key = "pk_exc_ign5_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "system_error", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isGreaterThan(0);
    }

    /**
     * recordExceptions에 지정한 예외만 실패로 기록되는 것을 검증한다.
     *
     * 흐름:
     *   recordExceptions(HttpServerErrorException) 설정
     *   → 403(HttpClientErrorException) 3건 → recordExceptions에 없음 → 성공으로 집계
     *   → 500(HttpServerErrorException) 2건 → recordExceptions에 포함 → 실패로 집계
     *
     * 핵심:
     *   recordExceptions에 없는 예외는 "성공"으로 처리된다 (ignore와 다름).
     */
    @Test
    void recordExceptions에_지정한_예외만_실패로_기록된다() {
        paymentClient.setChaosMode("NORMAL");

        // HttpServerErrorException만 recordExceptions에 포함
        CircuitBreaker cb = CircuitBreaker.of("exc-record-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class)
                        .build());
        TestLogger.attach(cb);

        // 403 → recordExceptions에 없으므로 성공으로 집계
        for (int i = 0; i < 3; i++) {
            String key = "pk_exc_rec4_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "reject_company", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(3);

        // 500 → recordExceptions에 포함 → 실패로 집계
        for (int i = 0; i < 2; i++) {
            String key = "pk_exc_rec5_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "system_error", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    /**
     * recordFailurePredicate로 커스텀 실패 판단 로직을 적용할 수 있는 것을 검증한다.
     *
     * 흐름:
     *   recordException(Predicate) 설정: 5xx와 timeout만 실패로 판단
     *   → 403 → Predicate false → 성공으로 집계
     *   → 500 → Predicate true → 실패로 집계
     *
     * 핵심:
     *   recordExceptions(Class)보다 유연한 판단이 필요할 때 Predicate를 사용한다.
     *   예: HTTP 상태 코드, 에러 메시지 내용 등으로 세밀하게 구분 가능.
     */
    @Test
    void recordFailurePredicate로_커스텀_실패_판단_5xx만_실패() {
        paymentClient.setChaosMode("NORMAL");

        CircuitBreaker cb = CircuitBreaker.of("exc-pred-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        // Predicate: 5xx와 timeout만 실패로 판단
                        .recordException(e ->
                                e instanceof HttpServerErrorException ||
                                e instanceof ResourceAccessException)
                        .build());
        TestLogger.attach(cb);

        // 403 → Predicate false → 성공으로 집계
        for (int i = 0; i < 3; i++) {
            String key = "pk_exc_pred4_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "reject_company", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);

        // 500 → Predicate true → 실패로 집계
        for (int i = 0; i < 2; i++) {
            String key = "pk_exc_pred5_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "system_error", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    /**
     * ignoreExceptions와 recordExceptions가 동시에 설정되었을 때 ignore가 우선하는 것을 검증한다.
     *
     * 흐름:
     *   recordExceptions(RuntimeException) + ignoreExceptions(HttpClientErrorException) 설정
     *   → HttpClientErrorException은 RuntimeException의 하위 클래스이지만
     *   → ignoreExceptions가 우선 → 무시됨 → CLOSED
     *
     * 핵심:
     *   ignoreExceptions가 recordExceptions보다 우선순위가 높다.
     *   두 설정이 충돌할 때 ignore가 이기므로, 비즈니스 에러를 안전하게 제외할 수 있다.
     */
    @Test
    void ignoreExceptions가_recordExceptions보다_우선한다() {
        paymentClient.setChaosMode("NORMAL");

        CircuitBreaker cb = CircuitBreaker.of("exc-priority-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        // RuntimeException은 HttpClientErrorException의 부모
                        .recordExceptions(RuntimeException.class)
                        // HttpClientErrorException을 ignore → RuntimeException에 해당하더라도 무시
                        .ignoreExceptions(HttpClientErrorException.class)
                        .build());
        TestLogger.attach(cb);

        // reject_company → HttpClientErrorException(403)
        // RuntimeException 하위지만 ignoreExceptions가 우선 → 무시됨
        for (int i = 0; i < 5; i++) {
            String key = "pk_exc_pri_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "reject_company", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }

        // ignoreExceptions가 우선 → 실패로 집계되지 않음 → CLOSED
        TestLogger.summary(cb);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
    }
}
