package com.example.resilience.retry;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RetryBasicTest extends ExampleTestBase {

    /**
     * 1회 실패 후 재시도에서 성공하는 시나리오를 검증한다.
     *
     * 흐름:
     *   DEAD 모드(첫 시도 실패) → Retry 이벤트 발생 시 NORMAL로 전환
     *   → 2번째 시도에서 성공
     *
     * 핵심:
     *   Retry의 이벤트 퍼블리셔를 활용하면 재시도 시점에 동적으로 행동을 변경할 수 있다.
     *   실전에서는 로깅이나 메트릭 수집에 활용한다.
     */
    @Test
    void 첫_시도_실패_후_재시도에서_성공한다() {
        paymentClient.setChaosMode("DEAD"); // 첫 시도 실패

        Retry retry = Retry.of("retry-recover-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        // 재시도 시 NORMAL로 전환 → 두 번째 시도는 성공
        retry.getEventPublisher().onRetry(event ->
                paymentClient.setChaosMode("NORMAL"));

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_retry_recover", "order_retry_recover", 10000));

        Map<String, Object> result = decorated.get(); // 2번째 시도에서 성공
        assertThat(result).isNotNull();
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
    }

    /**
     * DEAD 모드에서 3회 전부 실패하면 최종 예외가 발생하는 것을 검증한다.
     *
     * 흐름:
     *   DEAD 모드 → maxAttempts(3)회 전부 실패
     *   → 마지막 시도의 예외(HttpServerErrorException)가 호출자에게 전파됨
     *
     * 핵심:
     *   Retry는 모든 재시도가 실패하면 마지막 예외를 그대로 던진다.
     *   메트릭에서 numberOfFailedCallsWithRetryAttempt로 재시도 실패를 추적할 수 있다.
     */
    @Test
    void DEAD에서_3회_전부_실패하면_최종_예외가_발생한다() {
        paymentClient.setChaosMode("DEAD");

        Retry retry = Retry.of("retry-dead-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_retry_dead", "order_retry", 10000));

        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);

        // 재시도 포함 실패 1건 (내부적으로 3회 시도)
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    /**
     * TIMEOUT 모드에서 ResourceAccessException으로 재시도가 수행되는 것을 검증한다.
     *
     * 흐름:
     *   TIMEOUT 모드 + readTimeout=2s → ResourceAccessException 발생
     *   → retryExceptions에 포함 → 재시도 수행 → 2회 모두 실패
     *
     * 핵심:
     *   네트워크 타임아웃은 ResourceAccessException으로 래핑되며,
     *   이를 retryExceptions에 포함하면 타임아웃 시 재시도할 수 있다.
     */
    @Test
    void TIMEOUT에서_ResourceAccessException으로_재시도한다() {
        paymentClient.setChaosMode("TIMEOUT");
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 2000); // readTimeout=2s

        Retry retry = Retry.of("retry-timeout-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(2)
                .retryExceptions(ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_retry_to", "order_retry_to", 10000));

        assertThatThrownBy(decorated::get).isInstanceOf(ResourceAccessException.class);
    }

    /**
     * retryExceptions에 해당 예외가 없으면 재시도 없이 즉시 실패하는 것을 검증한다.
     *
     * 흐름:
     *   DEAD 모드(500 응답) → retryExceptions에 ResourceAccessException만 설정
     *   → HttpServerErrorException은 retryExceptions에 없음 → 재시도 없이 즉시 실패
     *
     * 핵심:
     *   retryExceptions에 포함되지 않은 예외는 재시도 대상이 아니다.
     *   의도하지 않은 예외까지 재시도하면 부하가 가중될 수 있으므로 명시적으로 지정해야 한다.
     */
    @Test
    void retryExceptions에_없는_예외는_재시도_없이_즉시_실패한다() {
        paymentClient.setChaosMode("DEAD");

        Retry retry = Retry.of("retry-no-match-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(ResourceAccessException.class) // HttpServerErrorException 없음
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_retry_no", "order_retry_no", 10000));

        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);

        // 재시도 없이 즉시 실패
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    /**
     * 비즈니스 에러(403)는 재시도 대상이 아닌 것을 검증한다.
     *
     * 흐름:
     *   NORMAL 모드 → reject_company 트리거 → 403(HttpClientErrorException)
     *   → retryExceptions에 HttpClientErrorException 없음 → 재시도 없이 즉시 실패
     *
     * 핵심:
     *   비즈니스 에러는 재시도해도 결과가 바뀌지 않으므로 재시도하면 안 된다.
     *   retryExceptions에 서버 에러(5xx)와 네트워크 에러만 포함하는 것이 올바른 설정이다.
     */
    @Test
    void 비즈니스_에러_403은_재시도하지_않는다() {
        paymentClient.setChaosMode("NORMAL");

        Retry retry = Retry.of("retry-biz-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_retry_biz", "reject_company", 10000));

        assertThatThrownBy(decorated::get).isInstanceOf(HttpClientErrorException.class);

        // 재시도 없이 즉시 실패
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    /**
     * waitDuration 설정으로 재시도 간 대기 시간이 적용되는 것을 검증한다.
     *
     * 흐름:
     *   DEAD 모드 + maxAttempts(3) + waitDuration(1s)
     *   → 3회 시도, 2번 대기(각 1초) → 최소 2초 소요
     *
     * 핵심:
     *   waitDuration은 재시도 간 대기 시간을 설정한다.
     *   대기 없이 즉시 재시도하면 이미 과부하 상태인 서버에 추가 부하를 줄 수 있다.
     *   적절한 대기 시간을 설정하여 서버 복구 여유를 주는 것이 중요하다.
     */
    @Test
    void waitDuration_설정시_재시도_간_대기_시간이_적용된다() {
        paymentClient.setChaosMode("DEAD");

        Retry retry = Retry.of("retry-wait-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1)) // 재시도 간 1초 대기
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_retry_wait", "order_retry_wait", 10000));

        long start = System.currentTimeMillis();
        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);
        long elapsed = System.currentTimeMillis() - start;

        // 3회 시도, 2번 대기(각 1초) → 최소 2초 소요
        assertThat(elapsed).isGreaterThanOrEqualTo(2000L);
    }

    /**
     * maxAttempts는 "초기 호출 포함" 총 시도 횟수이며, 재시도만의 횟수가 아닌 것을 검증한다.
     *
     * 흐름:
     *   DEAD 모드 + maxAttempts(3) → 실제 호출 3번 (초기 1회 + 재시도 2회)
     *
     * 핵심:
     *   maxAttempts(3)은 "최대 3번 재시도"가 아니라 "최대 3번 시도"를 의미한다.
     *   GitHub Issues에서 가장 흔한 혼란 사례 중 하나이다.
     *   이름이 "max retry attempts"가 아니라 "max attempts"이다.
     */
    @Test
    void maxAttempts는_초기_호출을_포함한_총_시도_횟수이다() {
        paymentClient.setChaosMode("DEAD");

        AtomicInteger actualCallCount = new AtomicInteger(0);

        Retry retry = Retry.of("max-attempts-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3) // 총 3번 시도 (초기 1 + 재시도 2)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            actualCallCount.incrementAndGet();
            return paymentClient.confirm("pk_max_att", "order_max_att", 10000);
        });

        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);

        // maxAttempts(3) = 실제 호출 3번 (초기 1 + 재시도 2), 4번이 아님
        assertThat(actualCallCount.get()).isEqualTo(3);

        // Retry 메트릭: 재시도 시도 횟수는 2 (초기 호출 제외)
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    /**
     * failAfterMaxAttempts는 결과 기반 재시도에만 적용되고, 예외 기반에는 적용되지 않는 것을 검증한다.
     *
     * 흐름:
     *   DEAD 모드 + maxAttempts(3) + failAfterMaxAttempts=true + retryExceptions
     *   → 3회 전부 예외 실패 → 원래 예외(HttpServerErrorException)가 그대로 전파
     *   → failAfterMaxAttempts=true인데도 MaxRetriesExceededException이 아님
     *
     * 핵심:
     *   failAfterMaxAttempts는 retryOnResult(결과 기반)에서만 동작한다.
     *   예외 기반 재시도에서는 설정과 무관하게 항상 원래 예외가 전파된다.
     *   이 구분을 모르면 "failAfterMaxAttempts=true로 했는데 왜 MaxRetriesExceededException이 안 나오지?"라는
     *   혼란이 생긴다.
     */
    @Test
    void failAfterMaxAttempts는_예외_기반_재시도에는_적용되지_않는다() {
        paymentClient.setChaosMode("DEAD");

        Retry retry = Retry.of("fail-exception-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .failAfterMaxAttempts(true) // true로 설정해도
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_fail_exc", "order_fail_exc", 10000));

        // 예외 기반이므로 원래 예외가 그대로 나옴 (MaxRetriesExceededException 아님)
        assertThatThrownBy(decorated::get)
                .isInstanceOf(HttpServerErrorException.class)
                .isNotInstanceOf(MaxRetriesExceededException.class);
    }

    /**
     * failAfterMaxAttempts=true + retryOnResult 조합에서 MaxRetriesExceededException이 발생하는 것을 검증한다.
     *
     * 흐름:
     *   maxAttempts(3) + failAfterMaxAttempts=true + retryOnResult(status=="IN_PROGRESS")
     *   → 3회 모두 IN_PROGRESS 반환 → MaxRetriesExceededException 발생
     *
     * 핵심:
     *   failAfterMaxAttempts=false(기본값)이면 마지막 결과가 그냥 반환된다.
     *   failAfterMaxAttempts=true이면 MaxRetriesExceededException을 던져 "실패"를 명확히 한다.
     *   CircuitBreaker와 조합 시, 이 예외가 CB 실패로 집계될 수 있으므로 주의가 필요하다.
     */
    @Test
    void failAfterMaxAttempts_true_결과_기반에서_MaxRetriesExceededException이_발생한다() {
        Retry retry = Retry.of("fail-result-" + UUID.randomUUID(),
                RetryConfig.<Map<String, Object>>custom()
                        .maxAttempts(3)
                        .failAfterMaxAttempts(true)
                        .retryOnResult(result -> "IN_PROGRESS".equals(result.get("status")))
                        .build());
        TestLogger.attach(retry);

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry,
                () -> Map.of("status", "IN_PROGRESS", "paymentKey", "pk_stuck"));

        // 결과 기반 + failAfterMaxAttempts=true → MaxRetriesExceededException
        assertThatThrownBy(decorated::get)
                .isInstanceOf(MaxRetriesExceededException.class);
    }
}
