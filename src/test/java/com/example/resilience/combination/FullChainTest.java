package com.example.resilience.combination;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FullChainTest extends ExampleTestBase {

    @BeforeEach
    void configureLongerTimeout() {
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 10000);
    }

    /**
     * Bulkhead → CB → Retry 3단 조합에서 각 레이어가 올바르게 동작하는 것을 검증한다.
     *
     * 흐름:
     *   Bulkhead(바깥, maxConcurrent=5) → CB(중간) → Retry(안쪽, maxAttempts=3) → client
     *   1. DEAD → 5건 동시 요청 → Retry 각 3회 시도 → 전부 실패
     *      → CB에 5건 실패 집계 (Retry 내부 재시도는 CB가 모름) → CB OPEN
     *   2. 추가 5건 요청 → CB OPEN → CallNotPermittedException (서버 도달 안 함)
     *      → Bulkhead 슬롯은 잡았다가 즉시 반환
     *
     * 핵심:
     *   공식 권장 순서 Bulkhead(outer) → CB → Retry(inner)에서:
     *   - Retry 재시도는 CB에 1건으로 집계 (CB 실패율 오염 방지)
     *   - CB OPEN 시 서버에 요청이 가지 않음 (Retry도 실행 안 됨)
     *   - Bulkhead는 동시성만 제한하고 결과에 관여하지 않음
     */
    @Test
    void Bulkhead_CB_Retry_3단_조합에서_각_레이어가_올바르게_동작한다() throws Exception {
        paymentClient.setChaosMode("DEAD");

        Bulkhead bulkhead = Bulkhead.of("chain-bh-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(5)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bulkhead);

        CircuitBreaker cb = CircuitBreaker.of("chain-cb-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        Retry retry = Retry.of("chain-retry-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        // Phase 1: 5건 동시 요청 → 각 Retry 3회 → 전부 실패 → CB에 5건 집계 → OPEN
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch readyLatch = new CountDownLatch(5);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try { startLatch.await(); } catch (InterruptedException e) { return; }

                String key = "pk_chain_" + idx;
                // 올바른 순서: Bulkhead(바깥) → CB → Retry(안쪽) → client
                Supplier<Map<String, Object>> retryDecorated = Retry.decorateSupplier(retry,
                        () -> paymentClient.confirm(key, "order_chain", 10000));
                Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb, retryDecorated);
                Supplier<Map<String, Object>> fullChain = Bulkhead.decorateSupplier(bulkhead, cbDecorated);

                try { fullChain.get(); } catch (Exception ignored) {}
            }));
        }

        readyLatch.await();
        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }

        // CB에 5건 실패 집계 (Retry 내부 3회 재시도는 CB가 모름)
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Phase 2: CB OPEN → 추가 요청은 서버 도달 없이 즉시 거절
        AtomicInteger callNotPermittedCount = new AtomicInteger(0);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            String key = "pk_chain_open_" + idx;
            Supplier<Map<String, Object>> retryDecorated = Retry.decorateSupplier(retry,
                    () -> paymentClient.confirm(key, "order_chain_open", 10000));
            Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb, retryDecorated);
            Supplier<Map<String, Object>> fullChain = Bulkhead.decorateSupplier(bulkhead, cbDecorated);

            try {
                fullChain.get();
            } catch (CallNotPermittedException e) {
                callNotPermittedCount.incrementAndGet();
            } catch (Exception ignored) {}
        }

        // CB OPEN → 5건 모두 CallNotPermittedException
        TestLogger.summary(cb);
        assertThat(callNotPermittedCount.get()).isEqualTo(5);
        assertThat(cb.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(5);

        // Bulkhead 슬롯은 정상 (CB 거절이 즉시 반환되므로 슬롯 점유 없음)
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(5);

        executor.shutdown();
    }

    /**
     * 3단 조합에서 Retry가 성공하면 CB에 성공으로, Bulkhead 슬롯도 정상 반환되는 것을 검증한다.
     *
     * 흐름:
     *   Bulkhead(바깥) → CB(중간) → Retry(안쪽, maxAttempts=3) → client
     *   → 1차 실패, 2차 실패, 재시도 시 NORMAL 전환 → 3차 성공
     *   → CB에 성공 1건 집계 (내부 실패 은닉)
     *   → Bulkhead 슬롯 정상 반환
     *
     * 핵심:
     *   전체 체인이 정상 복구 시나리오에서도 올바르게 동작하는지 확인.
     *   Retry가 실패를 흡수하면 CB와 Bulkhead 모두 영향을 받지 않는다.
     */
    @Test
    void 삼단_조합에서_Retry_성공시_CB_성공_집계되고_Bulkhead_슬롯_반환된다() {
        paymentClient.setChaosMode("DEAD");

        Bulkhead bulkhead = Bulkhead.of("chain-ok-bh-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(5)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bulkhead);

        CircuitBreaker cb = CircuitBreaker.of("chain-ok-cb-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        Retry retry = Retry.of("chain-ok-retry-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        // 2번째 재시도 전에 NORMAL로 전환 → 3차 성공
        final int[] attemptCount = {0};
        retry.getEventPublisher().onRetry(event -> {
            attemptCount[0]++;
            if (attemptCount[0] == 2) {
                paymentClient.setChaosMode("NORMAL");
            }
        });

        Supplier<Map<String, Object>> retryDecorated = Retry.decorateSupplier(retry,
                () -> paymentClient.confirm("pk_chain_ok", "order_chain_ok", 10000));
        Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb, retryDecorated);
        Supplier<Map<String, Object>> fullChain = Bulkhead.decorateSupplier(bulkhead, cbDecorated);

        Map<String, Object> result = fullChain.get();
        assertThat(result).isNotNull();

        // CB: 성공 1건, 실패 0건
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Bulkhead 슬롯 전부 반환
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(5);
    }
}
