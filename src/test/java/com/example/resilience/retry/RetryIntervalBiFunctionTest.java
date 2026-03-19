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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RetryIntervalBiFunctionTest extends ExampleTestBase {

    /**
     * intervalBiFunction으로 예외 종류에 따라 대기 시간을 동적으로 변경하는 것을 검증한다.
     *
     * 흐름:
     *   intervalBiFunction 설정:
     *     - HttpServerErrorException(500) → 1초 대기
     *     - ResourceAccessException(timeout) → 3초 대기
     *   → TIMEOUT 모드 + readTimeout=2s → ResourceAccessException 발생 → 3초 대기
     *   → 2회 시도, 1번 대기(3초) → 최소 3초 소요
     *
     * 핵심:
     *   고정 intervalFunction은 예외 종류와 무관하게 동일한 대기시간을 적용한다.
     *   intervalBiFunction은 예외/결과에 따라 대기시간을 동적으로 조절할 수 있다.
     *
     *   실무 예시:
     *   - 500(서버 에러) → 짧게 대기 후 재시도 (일시적 장애일 가능성)
     *   - timeout → 길게 대기 후 재시도 (서버가 과부하 상태)
     *   - 429(Too Many Requests) → Retry-After 헤더 값만큼 대기
     */
    @Test
    void intervalBiFunction으로_예외_종류별_대기시간을_동적으로_변경한다() {
        paymentClient.setChaosMode("TIMEOUT");
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 2000); // readTimeout=2s → ResourceAccessException

        Retry retry = Retry.of("interval-bi-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(2)
                .intervalBiFunction((attempts, either) -> {
                    // either.getLeft() = 예외, either.getRight() = 결과
                    if (either.isLeft()) {
                        Throwable ex = either.getLeft();
                        if (ex instanceof ResourceAccessException) {
                            return 3000L; // timeout → 3초 대기 (서버 과부하)
                        }
                        return 1000L; // 그 외 → 1초 대기
                    }
                    return 1000L; // 결과 기반 → 1초
                })
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        List<Long> callTimestamps = new ArrayList<>();
        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callTimestamps.add(System.currentTimeMillis());
            return paymentClient.confirm("pk_bi_timeout", "order_bi", 10000);
        });

        assertThatThrownBy(decorated::get).isInstanceOf(ResourceAccessException.class);
        assertThat(callTimestamps).hasSize(2);

        // ResourceAccessException → 3초 대기
        long interval = callTimestamps.get(1) - callTimestamps.get(0);
        // readTimeout(2s) + 대기(3s) → 간격 ~5s (readTimeout 소요 포함)
        assertThat(interval).isGreaterThanOrEqualTo(4500L);
    }

    /**
     * intervalBiFunction으로 500 에러 시 짧은 대기시간을 적용하는 것을 검증한다.
     *
     * 흐름:
     *   DEAD 모드(500) → HttpServerErrorException → intervalBiFunction → 1초 대기
     *   → 3회 시도, 2번 대기(각 1초) → 최소 2초 소요
     *
     * 핵심:
     *   위 timeout 테스트와 대비: 동일한 intervalBiFunction이지만
     *   예외 종류가 다르면 대기시간이 다르다.
     *   500은 서버의 일시적 오류일 수 있으므로 짧게 재시도하고,
     *   timeout은 서버 과부하이므로 길게 대기한다.
     */
    @Test
    void intervalBiFunction으로_500에러시_짧은_대기시간을_적용한다() {
        paymentClient.setChaosMode("DEAD");

        Retry retry = Retry.of("interval-bi-500-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .intervalBiFunction((attempts, either) -> {
                    if (either.isLeft()) {
                        Throwable ex = either.getLeft();
                        if (ex instanceof ResourceAccessException) {
                            return 3000L;
                        }
                        return 1000L; // 500 → 1초
                    }
                    return 1000L;
                })
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        List<Long> callTimestamps = new ArrayList<>();
        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callTimestamps.add(System.currentTimeMillis());
            return paymentClient.confirm("pk_bi_500", "order_bi_500", 10000);
        });

        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);
        assertThat(callTimestamps).hasSize(3);

        // HttpServerErrorException → 1초 대기
        long interval1 = callTimestamps.get(1) - callTimestamps.get(0);
        long interval2 = callTimestamps.get(2) - callTimestamps.get(1);
        assertThat(interval1).isBetween(800L, 1500L);
        assertThat(interval2).isBetween(800L, 1500L);

        // 총 소요시간 ~2초 (1초 x 2번 대기)
        long total = callTimestamps.get(2) - callTimestamps.get(0);
        assertThat(total).isBetween(1800L, 2500L);
    }

    /**
     * intervalBiFunction으로 결과 기반 재시도 시에도 대기시간을 동적으로 변경하는 것을 검증한다.
     *
     * 흐름:
     *   retryOnResult(status=="IN_PROGRESS") + intervalBiFunction
     *   → 결과가 IN_PROGRESS → either.isRight() → 2초 대기
     *   → 2회 시도, 1번 대기(2초) → 최소 2초 소요
     *
     * 핵심:
     *   intervalBiFunction은 예외(Either.left)뿐 아니라
     *   결과 기반 재시도(Either.right)에서도 동작한다.
     *   PG API가 "처리중" 상태를 반환하면 적절한 폴링 간격으로 재시도할 수 있다.
     */
    @Test
    void intervalBiFunction은_결과_기반_재시도에서도_동작한다() {
        AtomicInteger callCount = new AtomicInteger(0);

        Retry retry = Retry.of("interval-bi-result-" + UUID.randomUUID(),
                RetryConfig.<Map<String, Object>>custom()
                        .maxAttempts(2)
                        .retryOnResult(result -> "IN_PROGRESS".equals(result.get("status")))
                        .intervalBiFunction((attempts, either) -> {
                            if (either.isRight()) {
                                return 2000L; // 결과 기반 → 2초 대기 (폴링 간격)
                            }
                            return 1000L; // 예외 → 1초
                        })
                        .build());
        TestLogger.attach(retry);

        List<Long> callTimestamps = new ArrayList<>();
        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callTimestamps.add(System.currentTimeMillis());
            callCount.incrementAndGet();
            return Map.of("status", "IN_PROGRESS", "paymentKey", "pk_bi_poll");
        });

        Map<String, Object> result = decorated.get();
        assertThat(result.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(callCount.get()).isEqualTo(2);

        // 결과 기반 → 2초 대기
        long interval = callTimestamps.get(1) - callTimestamps.get(0);
        assertThat(interval).isBetween(1800L, 2500L);
    }
}
