package com.example.resilience.retry;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RetryResultPredicateTest extends ExampleTestBase {

    /**
     * 응답 결과값이 조건에 맞으면 예외 없이도 재시도하는 것을 검증한다.
     *
     * 흐름:
     *   1~2차: status="IN_PROGRESS" 반환 (예외 없음, HTTP 200)
     *   → retryOnResult predicate가 "IN_PROGRESS"를 감지 → 재시도
     *   3차: status="DONE" 반환 → predicate 통과 → 성공
     *
     * 핵심:
     *   실제 PG API는 결제 승인 시 "아직 처리중(IN_PROGRESS)"을 HTTP 200으로 반환할 수 있다.
     *   이 경우 예외가 발생하지 않으므로 retryExceptions로는 재시도가 불가능하다.
     *   retryOnResult를 사용하면 응답 본문의 비즈니스 상태를 기준으로 재시도할 수 있다.
     */
    @Test
    void 응답_status가_IN_PROGRESS이면_예외_없이도_재시도한다() {
        paymentClient.setChaosMode("NORMAL");
        AtomicInteger callCount = new AtomicInteger(0);

        Retry retry = Retry.of("result-predicate-" + UUID.randomUUID(),
                RetryConfig.<Map<String, Object>>custom()
                        .maxAttempts(3)
                        .retryOnResult(result -> "IN_PROGRESS".equals(result.get("status")))
                        .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(retry);

        // 1~2차: IN_PROGRESS 반환, 3차: 실제 Mock 서버 호출 → DONE
        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            int count = callCount.incrementAndGet();
            if (count <= 2) {
                // PG사가 "아직 처리중"을 200 OK로 반환하는 시나리오
                return Map.of("status", "IN_PROGRESS", "paymentKey", "pk_pending");
            }
            return paymentClient.confirm("pk_result_ok", "order_result_ok", 10000);
        });

        Map<String, Object> result = decorated.get();

        // 3차에서 DONE 반환 → 성공
        assertThat(result.get("status")).isEqualTo("DONE");
        assertThat(callCount.get()).isEqualTo(3);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
    }

    /**
     * 모든 시도에서 predicate가 매칭되면 마지막 결과가 반환되는 것을 검증한다.
     * (예외가 아닌 결과를 반환하는 점이 retryExceptions와의 핵심 차이)
     *
     * 흐름:
     *   maxAttempts(3) + 3차 모두 status="IN_PROGRESS"
     *   → 3번 재시도 후 마지막 결과(IN_PROGRESS)를 그대로 반환
     *
     * 핵심:
     *   retryExceptions는 모든 시도 실패 시 마지막 예외를 던진다.
     *   retryOnResult는 모든 시도 매칭 시 마지막 결과를 반환한다 — 예외 대신 값이 돌아온다.
     *   호출자는 반환값의 status를 확인하고 적절히 대응해야 한다.
     *   메트릭은 "재시도 후 실패"로 집계된다.
     */
    @Test
    void 모든_시도에서_predicate_매칭시_마지막_결과가_반환된다() {
        AtomicInteger callCount = new AtomicInteger(0);

        Retry retry = Retry.of("result-all-match-" + UUID.randomUUID(),
                RetryConfig.<Map<String, Object>>custom()
                        .maxAttempts(3)
                        .retryOnResult(result -> "IN_PROGRESS".equals(result.get("status")))
                        .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callCount.incrementAndGet();
            return Map.of("status", "IN_PROGRESS", "paymentKey", "pk_stuck");
        });

        // 예외가 아닌 결과가 반환됨
        Map<String, Object> result = decorated.get();

        // 예외가 아닌 결과가 반환됨 — retryExceptions와의 핵심 차이
        assertThat(result.get("status")).isEqualTo("IN_PROGRESS");
        // maxAttempts(3) = 총 3번 시도
        assertThat(callCount.get()).isEqualTo(3);
        // 모든 시도가 predicate 매칭 → "재시도 후 실패"로 집계
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    /**
     * retryOnResult와 retryExceptions를 함께 사용할 때 둘 다 동작하는 것을 검증한다.
     *
     * 흐름:
     *   1차: 500 에러 (예외) → retryExceptions로 재시도
     *   2차: 200 OK + IN_PROGRESS (결과) → retryOnResult로 재시도
     *   3차: 200 OK + DONE → 성공
     *
     * 핵심:
     *   실무에서는 예외와 결과값 모두를 재시도 조건으로 설정해야 한다.
     *   retryExceptions는 네트워크/서버 장애를, retryOnResult는 비즈니스 미완료 상태를 커버한다.
     */
    @Test
    void retryOnResult와_retryExceptions를_함께_사용하면_둘_다_재시도_트리거된다() {
        AtomicInteger callCount = new AtomicInteger(0);

        Retry retry = Retry.of("result-mixed-" + UUID.randomUUID(),
                RetryConfig.<Map<String, Object>>custom()
                        .maxAttempts(3)
                        .retryOnResult(result -> "IN_PROGRESS".equals(result.get("status")))
                        .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // 1차: 서버 에러 → retryExceptions로 재시도
                paymentClient.setChaosMode("DEAD");
                return paymentClient.confirm("pk_mixed_fail", "order_mixed", 10000);
            }
            if (count == 2) {
                // 2차: 200 OK + IN_PROGRESS → retryOnResult로 재시도
                paymentClient.setChaosMode("NORMAL");
                return Map.of("status", "IN_PROGRESS", "paymentKey", "pk_mixed_pending");
            }
            // 3차: 정상 응답
            return paymentClient.confirm("pk_mixed_ok", "order_mixed_ok", 10000);
        });

        Map<String, Object> result = decorated.get();

        assertThat(result.get("status")).isEqualTo("DONE");
        assertThat(callCount.get()).isEqualTo(3);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
    }
}
