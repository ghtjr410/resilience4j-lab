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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ExceptionInheritanceTest extends ExampleTestBase {

    /**
     * recordExceptions에 부모 예외를 지정하면 자식 예외도 실패로 집계되는 것을 검증한다.
     *
     * 흐름:
     *   recordExceptions(HttpStatusCodeException.class) 설정
     *   → HttpServerErrorException(500)은 HttpStatusCodeException의 자식
     *   → 5건 실패 → 실패율 100% → OPEN
     *
     * 핵심:
     *   recordExceptions는 instanceof로 판단한다.
     *   부모 예외를 지정하면 모든 자식 예외가 실패로 집계된다.
     *   공식 문서에서 recordExceptions(IOException.class, TimeoutException.class) 패턴을
     *   보여주는 이유 — IOException 하위의 모든 네트워크 예외를 한 번에 잡기 위함이다.
     */
    @Test
    void recordExceptions에_부모_예외_지정시_자식_예외도_실패로_집계된다() {
        paymentClient.setChaosMode("DEAD");

        // HttpStatusCodeException = HttpServerErrorException + HttpClientErrorException의 부모
        CircuitBreaker cb = CircuitBreaker.of("inherit-parent-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpStatusCodeException.class) // 부모 지정
                        .build());
        TestLogger.attach(cb);

        // HttpServerErrorException(500) → HttpStatusCodeException의 자식 → 실패 집계
        for (int i = 0; i < 5; i++) {
            String key = "pk_inherit_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "system_error", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }

        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * 부모 예외(HttpStatusCodeException) 지정 시 4xx 자식도 실패로 집계되어 오진이 발생하는 것을 검증한다.
     *
     * 흐름:
     *   recordExceptions(HttpStatusCodeException.class) 설정
     *   → reject_company(403) = HttpClientErrorException → HttpStatusCodeException의 자식
     *   → 5건 비즈니스 에러 → 실패 집계 → OPEN (오진!)
     *
     * 핵심:
     *   부모 예외를 넓게 잡으면 의도하지 않은 자식 예외까지 실패로 집계된다.
     *   HttpStatusCodeException을 record하면 4xx(비즈니스)와 5xx(장애) 구분이 안 된다.
     *   이때 ignoreExceptions(HttpClientErrorException.class)로 4xx를 제외해야 한다.
     */
    @Test
    void 부모_예외_지정시_4xx_자식도_실패로_집계되어_오진이_발생한다() {
        paymentClient.setChaosMode("NORMAL");

        CircuitBreaker cb = CircuitBreaker.of("inherit-misuse-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpStatusCodeException.class) // 부모 → 4xx도 잡힘
                        .build());
        TestLogger.attach(cb);

        // 403(HttpClientErrorException) → HttpStatusCodeException의 자식 → 실패로 집계 (오진!)
        for (int i = 0; i < 5; i++) {
            String key = "pk_inherit_mis_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "reject_company", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }

        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN); // 오진!
    }

    /**
     * 부모 예외 record + ignoreExceptions로 자식 예외를 선별적으로 제외하는 올바른 패턴을 검증한다.
     *
     * 흐름:
     *   recordExceptions(HttpStatusCodeException.class) + ignoreExceptions(HttpClientErrorException.class)
     *   → 403(HttpClientErrorException) → ignore 우선 → 무시
     *   → 500(HttpServerErrorException) → record에 해당 + ignore 아님 → 실패 집계
     *
     * 핵심:
     *   공식 문서 권장 패턴: recordExceptions(넓게) + ignoreExceptions(좁게 제외).
     *   부모로 한 번에 잡되, 비즈니스 에러는 명시적으로 제외한다.
     *   ExceptionHandlingTest의 "ignoreExceptions가_recordExceptions보다_우선한다"와
     *   동일 원리이지만, 예외 상속 관점에서 다시 검증한다.
     */
    @Test
    void 부모_예외_record와_ignoreExceptions로_자식을_선별적으로_제외한다() {
        paymentClient.setChaosMode("NORMAL");

        CircuitBreaker cb = CircuitBreaker.of("inherit-correct-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(10)
                        .recordExceptions(HttpStatusCodeException.class)       // 부모 → 넓게 잡기
                        .ignoreExceptions(HttpClientErrorException.class)       // 4xx 제외
                        .build());
        TestLogger.attach(cb);

        // 403 5건 → ignore → 실패 0건
        for (int i = 0; i < 5; i++) {
            String key = "pk_inherit_ign_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "reject_company", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 500 5건 → record에 해당 + ignore 아님 → 실패 집계
        for (int i = 0; i < 5; i++) {
            String key = "pk_inherit_rec_" + i;
            Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "system_error", 10000));
            try { decorated.get(); } catch (Exception ignored) {}
        }

        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isGreaterThan(0);
    }
}
