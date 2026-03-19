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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RetryExceptionFilterTest extends ExampleTestBase {

    /**
     * retryExceptions에 포함된 예외만 재시도 대상인 것을 검증한다.
     *
     * 흐름:
     *   DEAD 모드(500 응답) + retryExceptions에 ResourceAccessException만 설정
     *   → HttpServerErrorException은 대상 아님 → 재시도 없이 즉시 실패
     *
     * 핵심:
     *   retryExceptions는 화이트리스트 방식으로 동작한다.
     *   명시적으로 포함하지 않은 예외는 재시도하지 않으므로,
     *   어떤 에러를 재시도할지 신중하게 결정해야 한다.
     */
    @Test
    void retryExceptions에_포함된_예외만_재시도_대상이다() {
        paymentClient.setChaosMode("DEAD"); // 500 응답

        Retry retry = Retry.of("filter-rae-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(ResourceAccessException.class) // timeout만 재시도
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_filter1", "order_filter1", 10000));

        // HttpServerErrorException은 retryExceptions에 없으므로 재시도 없이 즉시 실패
        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    /**
     * 비즈니스 에러(403)는 재시도하지 않는 것을 검증한다.
     *
     * 흐름:
     *   NORMAL 모드 → reject_company 트리거 → 403(HttpClientErrorException)
     *   → retryExceptions에 HttpClientErrorException 없음 → 재시도 없이 즉시 실패
     *
     * 핵심:
     *   비즈니스 에러(4xx)는 클라이언트 측 문제이므로 재시도해도 결과가 바뀌지 않는다.
     *   retryExceptions에 포함하지 않아 불필요한 재시도를 방지해야 한다.
     */
    @Test
    void 비즈니스_에러_403은_재시도하지_않는다() {
        paymentClient.setChaosMode("NORMAL");

        Retry retry = Retry.of("filter-biz-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        // reject_company → 403
        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_filter2", "reject_company", 10000));

        // HttpClientErrorException은 retryExceptions에 없으므로 재시도 없이 즉시 실패
        assertThatThrownBy(decorated::get).isInstanceOf(HttpClientErrorException.class);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(1);
    }
}
