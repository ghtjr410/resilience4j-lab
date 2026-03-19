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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RetryIgnoreExceptionsTest extends ExampleTestBase {

    /**
     * ignoreExceptions에 지정된 예외는 재시도 없이 즉시 전파되는 것을 검증한다.
     *
     * 흐름:
     *   retryExceptions(HttpServerErrorException, ResourceAccessException)
     *   + ignoreExceptions(HttpClientErrorException)
     *   → reject_company(403 = HttpClientErrorException) → ignore → 재시도 없이 즉시 전파
     *
     * 핵심:
     *   ignoreExceptions는 retryExceptions와 반대 개념이다.
     *   retryExceptions: "이 예외가 나면 재시도하라" (화이트리스트)
     *   ignoreExceptions: "이 예외가 나면 절대 재시도하지 마라" (블랙리스트)
     *
     *   retryExceptions에 없는 예외도 재시도 안 되지만,
     *   ignoreExceptions는 명시적으로 "재시도 금지"를 선언하여 의도를 분명히 한다.
     */
    @Test
    void ignoreExceptions에_지정된_예외는_재시도_없이_즉시_전파된다() {
        paymentClient.setChaosMode("NORMAL");
        AtomicInteger callCount = new AtomicInteger(0);

        Retry retry = Retry.of("ignore-basic-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .ignoreExceptions(HttpClientErrorException.class) // 4xx 명시적 재시도 금지
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callCount.incrementAndGet();
            return paymentClient.confirm("pk_ign_exc", "reject_company", 10000);
        });

        // 재시도 없이 즉시 403 전파
        assertThatThrownBy(decorated::get).isInstanceOf(HttpClientErrorException.class);
        assertThat(callCount.get()).isEqualTo(1); // 1번만 호출
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    /**
     * ignoreExceptions와 retryExceptions에 동일 예외 지정 시 ignore가 우선하는 것을 검증한다.
     *
     * 흐름:
     *   retryExceptions(HttpServerErrorException) + ignoreExceptions(HttpServerErrorException)
     *   → DEAD(500) → HttpServerErrorException 발생
     *   → retryExceptions에도 있고 ignoreExceptions에도 있음 → ignore 우선 → 재시도 없이 즉시 전파
     *
     * 핵심:
     *   CB의 "ignoreExceptions가_recordExceptions보다_우선한다"와 동일한 원리.
     *   Retry에서도 ignore가 retry보다 우선순위가 높다.
     *   설정이 충돌할 때 ignore가 이기므로, 특정 예외를 확실히 재시도하지 않으려면
     *   ignoreExceptions에 넣으면 된다.
     */
    @Test
    void ignoreExceptions와_retryExceptions에_동일_예외_지정시_ignore가_우선한다() {
        paymentClient.setChaosMode("DEAD");
        AtomicInteger callCount = new AtomicInteger(0);

        Retry retry = Retry.of("ignore-priority-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class)  // 재시도 대상
                .ignoreExceptions(HttpServerErrorException.class)  // 동시에 무시 대상
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callCount.incrementAndGet();
            return paymentClient.confirm("pk_ign_pri", "order_ign_pri", 10000);
        });

        // ignore 우선 → 재시도 없이 즉시 전파
        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    /**
     * ignoreExceptions에 부모 예외 지정 시 자식 예외도 재시도가 무시되는 것을 검증한다.
     *
     * 흐름:
     *   retryExceptions(RuntimeException) + ignoreExceptions(HttpStatusCodeException)
     *   → 500(HttpServerErrorException) → HttpStatusCodeException의 자식 → ignore 우선 → 재시도 없음
     *   → TIMEOUT(ResourceAccessException) → HttpStatusCodeException 아님 → retry 대상 → 재시도 수행
     *
     * 핵심:
     *   CB의 ExceptionInheritanceTest와 동일한 예외 계층 상속 패턴.
     *   부모를 ignore하면 자식도 모두 ignore된다.
     *   넓은 부모(RuntimeException)를 retry하되, 특정 계열(HttpStatusCodeException)만 제외할 수 있다.
     */
    @Test
    void ignoreExceptions에_부모_예외_지정시_자식도_재시도가_무시된다() {
        AtomicInteger callCount = new AtomicInteger(0);

        Retry retry = Retry.of("ignore-inherit-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(RuntimeException.class)           // 넓게 재시도
                .ignoreExceptions(HttpStatusCodeException.class)   // HTTP 예외 계열 제외
                .build());
        TestLogger.attach(retry);

        // 500(HttpServerErrorException → HttpStatusCodeException 자식) → ignore → 즉시 전파
        paymentClient.setChaosMode("DEAD");
        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callCount.incrementAndGet();
            return paymentClient.confirm("pk_ign_inh", "order_ign_inh", 10000);
        });

        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);
        assertThat(callCount.get()).isEqualTo(1); // ignore → 1번만 호출
    }
}
