package com.example.resilience.retry;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
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
class RetryExceptionPredicateTest extends ExampleTestBase {

    /**
     * retryOnException(Predicate)로 커스텀 재시도 조건을 적용하는 것을 검증한다.
     *
     * 흐름:
     *   retryOnException(e -> e instanceof HttpServerErrorException || e instanceof ResourceAccessException)
     *   → 500(HttpServerErrorException) → predicate true → 재시도
     *   → 3회 전부 실패
     *
     * 핵심:
     *   retryExceptions(Class...)는 클래스 리스트로 재시도 대상을 지정한다.
     *   retryOnException(Predicate)는 Predicate로 더 세밀한 조건을 적용할 수 있다.
     *   CB의 recordException(Predicate)와 대응되는 Retry 측 기능이다.
     */
    @Test
    void retryOnException_Predicate로_커스텀_재시도_조건을_적용한다() {
        paymentClient.setChaosMode("DEAD");
        AtomicInteger callCount = new AtomicInteger(0);

        Retry retry = Retry.of("pred-basic-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryOnException(e ->
                        e instanceof HttpServerErrorException ||
                        e instanceof ResourceAccessException)
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callCount.incrementAndGet();
            return paymentClient.confirm("pk_pred", "order_pred", 10000);
        });

        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);
        assertThat(callCount.get()).isEqualTo(3); // 3회 시도
    }

    /**
     * retryOnException Predicate가 false를 반환하면 재시도 없이 즉시 실패하는 것을 검증한다.
     *
     * 흐름:
     *   retryOnException(e -> e instanceof ResourceAccessException) — 타임아웃만 재시도
     *   → 500(HttpServerErrorException) → predicate false → 재시도 없이 즉시 전파
     *
     * 핵심:
     *   Predicate가 false를 반환하면 retryExceptions에 없는 예외와 동일하게 동작한다.
     */
    @Test
    void Predicate_false이면_재시도_없이_즉시_실패한다() {
        paymentClient.setChaosMode("DEAD");
        AtomicInteger callCount = new AtomicInteger(0);

        Retry retry = Retry.of("pred-false-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryOnException(e -> e instanceof ResourceAccessException) // 타임아웃만
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callCount.incrementAndGet();
            return paymentClient.confirm("pk_pred_f", "order_pred_f", 10000);
        });

        // 500 → predicate false → 즉시 전파
        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    /**
     * Predicate로 HTTP 상태 코드별 세밀한 재시도 제어를 적용하는 것을 검증한다.
     *
     * 흐름:
     *   Predicate: 5xx만 재시도, 4xx는 재시도하지 않음
     *   → reject_company(403) → predicate false → 즉시 전파
     *   → system_error(500) → predicate true → 재시도
     *
     * 핵심:
     *   retryExceptions(HttpServerErrorException.class)와 기능적으로 동일하지만,
     *   Predicate 방식은 예외의 메시지, HTTP 상태 코드, 헤더 등으로 더 세밀하게 분기할 수 있다.
     *   예: 429(Too Many Requests)만 재시도하고 나머지 4xx는 제외하는 등.
     */
    @Test
    void Predicate로_HTTP_상태_코드별_세밀한_재시도_제어가_가능하다() {
        paymentClient.setChaosMode("NORMAL");

        Retry retry = Retry.of("pred-status-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryOnException(e -> {
                    // 5xx만 재시도, 4xx는 즉시 전파
                    if (e instanceof HttpServerErrorException) return true;
                    if (e instanceof ResourceAccessException) return true;
                    return false;
                })
                .build());
        TestLogger.attach(retry);

        // 403 → predicate false → 즉시 전파
        AtomicInteger bizCallCount = new AtomicInteger(0);
        Supplier<Map<String, Object>> bizDecorated = Retry.decorateSupplier(retry, () -> {
            bizCallCount.incrementAndGet();
            return paymentClient.confirm("pk_pred_biz", "reject_company", 10000);
        });
        assertThatThrownBy(bizDecorated::get).isInstanceOf(HttpClientErrorException.class);
        assertThat(bizCallCount.get()).isEqualTo(1);

        // 500 → predicate true → 재시도 (새 Retry 인스턴스 필요)
        Retry retry2 = Retry.of("pred-status2-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryOnException(e ->
                        e instanceof HttpServerErrorException ||
                        e instanceof ResourceAccessException)
                .build());
        TestLogger.attach(retry2);

        AtomicInteger srvCallCount = new AtomicInteger(0);
        Supplier<Map<String, Object>> srvDecorated = Retry.decorateSupplier(retry2, () -> {
            srvCallCount.incrementAndGet();
            return paymentClient.confirm("pk_pred_srv", "system_error", 10000);
        });
        assertThatThrownBy(srvDecorated::get).isInstanceOf(HttpServerErrorException.class);
        assertThat(srvCallCount.get()).isEqualTo(3); // 3회 재시도
    }
}
