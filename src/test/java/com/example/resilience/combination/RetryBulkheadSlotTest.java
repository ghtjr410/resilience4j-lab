package com.example.resilience.combination;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
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
class RetryBulkheadSlotTest extends ExampleTestBase {

    @BeforeEach
    void configureLongerTimeout() {
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 10000);
    }

    /**
     * Retry(안쪽) → Bulkhead(바깥) 올바른 순서에서는 재시도가 슬롯을 추가 점유하지 않는 것을 검증한다.
     *
     * 흐름:
     *   Bulkhead(바깥, maxConcurrent=3) → Retry(안쪽, maxAttempts=3) → client
     *   → SLOW 2s + DEAD 모드 → 3건 동시 요청
     *   → 각 요청이 Bulkhead 슬롯 1개를 잡고, 그 안에서 Retry 3회 시도
     *   → 재시도가 같은 슬롯 안에서 실행됨 → 슬롯 추가 점유 없음
     *
     * 핵심:
     *   올바른 순서에서 Retry는 Bulkhead 안쪽에서 실행된다.
     *   재시도가 발생해도 이미 잡은 슬롯 안에서 실행되므로 동시성 리소스를 추가 소모하지 않는다.
     */
    @Test
    void Bulkhead_바깥_Retry_안쪽이면_재시도가_슬롯을_추가_점유하지_않는다() throws InterruptedException {
        paymentClient.setChaosMode("DEAD");

        Bulkhead bh = Bulkhead.of("slot-correct-bh-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(3)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bh);

        Retry retry = Retry.of("slot-correct-retry-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch readyLatch = new CountDownLatch(3);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger bhRejected = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try { startLatch.await(); } catch (InterruptedException e) { return; }

                // 올바른 순서: Bulkhead(바깥) → Retry(안쪽)
                Supplier<Map<String, Object>> retryDecorated = Retry.decorateSupplier(retry,
                        () -> paymentClient.confirm("pk_slot_c_" + idx, "order_slot_c", 10000));
                Supplier<Map<String, Object>> fullChain = Bulkhead.decorateSupplier(bh, retryDecorated);

                try { fullChain.get(); } catch (BulkheadFullException e) {
                    bhRejected.incrementAndGet();
                } catch (Exception ignored) {}
            }));
        }

        readyLatch.await();
        startLatch.countDown();
        for (Future<?> f : futures) {
            try { f.get(30, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();

        // 3건 모두 슬롯 확보 성공 (재시도가 슬롯을 추가 점유하지 않음)
        assertThat(bhRejected.get()).isEqualTo(0);
        // 슬롯 전부 반환
        assertThat(bh.getMetrics().getAvailableConcurrentCalls()).isEqualTo(3);
    }

    /**
     * Retry(바깥) → Bulkhead(안쪽) 잘못된 순서에서 재시도가 슬롯을 추가 점유하여
     * BulkheadFullException이 발생할 수 있는 것을 검증한다.
     *
     * 흐름:
     *   Retry(바깥, maxAttempts=3) → Bulkhead(안쪽, maxConcurrent=2) → client
     *   → SLOW 3s → 2건 동시 요청 → 슬롯 2개 점유
     *   → 1번째 실패 → Retry 재시도 → 슬롯 반환 후 다시 확보 시도
     *   → 하지만 다른 요청이 슬롯을 점유 중이면 BulkheadFullException
     *
     * 핵심:
     *   잘못된 순서에서 Retry는 Bulkhead 바깥에서 실행된다.
     *   재시도할 때마다 Bulkhead 슬롯을 새로 잡아야 한다.
     *   동시 요청이 많으면 재시도가 슬롯을 경쟁하여 BulkheadFullException이 발생한다.
     *   이는 "서버는 복구됐지만 재시도가 슬롯 경쟁으로 실패"하는 오작동이다.
     */
    @Test
    void Retry_바깥_Bulkhead_안쪽이면_재시도가_슬롯을_경쟁한다() throws InterruptedException {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));

        // maxConcurrent=2, maxWait=0 → 슬롯 없으면 즉시 거절
        Bulkhead bh = Bulkhead.of("slot-wrong-bh-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(2)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bh);

        // BulkheadFullException도 재시도 대상에 포함
        Retry retry = Retry.of("slot-wrong-retry-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class,
                        BulkheadFullException.class)
                .build());
        TestLogger.attach(retry);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch readyLatch = new CountDownLatch(3);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger bhRejectedFinal = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try { startLatch.await(); } catch (InterruptedException e) { return; }

                // 잘못된 순서: Retry(바깥) → Bulkhead(안쪽)
                Supplier<Map<String, Object>> bhDecorated = Bulkhead.decorateSupplier(bh,
                        () -> paymentClient.confirm("pk_slot_w_" + idx, "order_slot_w", 10000));
                Supplier<Map<String, Object>> fullChain = Retry.decorateSupplier(retry, bhDecorated);

                try { fullChain.get(); } catch (BulkheadFullException e) {
                    bhRejectedFinal.incrementAndGet();
                } catch (Exception ignored) {}
            }));
        }

        readyLatch.await();
        startLatch.countDown();
        for (Future<?> f : futures) {
            try { f.get(30, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();

        // 3건 중 1건은 최초부터 슬롯 부족 → Retry가 BulkheadFullException을 재시도
        // 하지만 다른 요청이 SLOW 3s로 슬롯을 점유 중 → 재시도해도 거절될 가능성 높음
        // 최종적으로 BulkheadFullException으로 실패하는 요청이 발생
        assertThat(bhRejectedFinal.get()).isGreaterThanOrEqualTo(1);
    }

    /**
     * CB OPEN 상태에서 Retry의 retryExceptions에 CallNotPermittedException이 없으면
     * 재시도 없이 즉시 실패하는 것을 검증한다.
     *
     * 흐름:
     *   CB(바깥) → Retry(안쪽) → client
     *   → CB OPEN → CallNotPermittedException
     *   → retryExceptions에 CallNotPermittedException 없음 → 재시도 없이 즉시 전파
     *
     * 핵심:
     *   올바른 동작: CB가 OPEN이면 Retry도 실행되지 않아야 한다.
     *   CB 바깥 → Retry 안쪽 순서에서는 CB가 먼저 차단하므로 Retry에 도달하지 않는다.
     *   반대로 Retry 바깥 → CB 안쪽 순서에서는 Retry가 CallNotPermittedException을
     *   재시도할지 결정해야 한다.
     */
    @Test
    void CB_OPEN시_retryExceptions에_CallNotPermittedException이_없으면_즉시_실패한다() {
        paymentClient.setChaosMode("DEAD");

        io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.of("cnpe-cb-" + UUID.randomUUID(),
                        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                                .failureRateThreshold(50)
                                .minimumNumberOfCalls(5)
                                .slidingWindowSize(5)
                                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                                .build());
        TestLogger.attach(cb);

        Retry retry = Retry.of("cnpe-retry-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                // CallNotPermittedException은 retryExceptions에 없음!
                .build());
        TestLogger.attach(retry);

        // CB를 OPEN으로 만듦
        for (int i = 0; i < 5; i++) {
            String key = "pk_cnpe_" + i;
            Supplier<Map<String, Object>> cbDecorated =
                    io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(cb,
                            () -> paymentClient.confirm(key, "order_cnpe", 10000));
            try { cbDecorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);

        // Retry(바깥) → CB(안쪽) 순서에서 CB OPEN
        AtomicInteger callCount = new AtomicInteger(0);
        Supplier<Map<String, Object>> cbDecorated =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(cb, () -> {
                    callCount.incrementAndGet();
                    return paymentClient.confirm("pk_cnpe_retry", "order_cnpe_retry", 10000);
                });
        Supplier<Map<String, Object>> retryDecorated = Retry.decorateSupplier(retry, cbDecorated);

        try {
            retryDecorated.get();
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // CallNotPermittedException이 retryExceptions에 없으므로 재시도 없이 즉시 전파
        }

        // 서버에 한 번도 도달하지 않음 (CB가 차단)
        assertThat(callCount.get()).isEqualTo(0);
        // Retry 재시도 없음
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    /**
     * CB OPEN 상태에서 retryExceptions에 CallNotPermittedException을 넣으면
     * Retry가 CB 차단을 무시하고 재시도하는 오작동을 검증한다.
     *
     * 흐름:
     *   Retry(바깥) → CB(안쪽) → client
     *   → CB OPEN → CallNotPermittedException
     *   → retryExceptions에 CallNotPermittedException 포함 → Retry가 재시도!
     *   → 3회 모두 CallNotPermittedException → 무의미한 재시도
     *
     * 핵심:
     *   CallNotPermittedException을 retryExceptions에 넣으면
     *   "CB가 의도적으로 차단한 요청"을 Retry가 무시하고 반복 시도한다.
     *   서버 보호를 위해 CB가 열렸는데, Retry가 이를 무력화하는 것이다.
     *   절대 CallNotPermittedException을 retryExceptions에 포함하면 안 된다.
     */
    @Test
    void CB_OPEN시_retryExceptions에_CallNotPermittedException을_넣으면_무의미한_재시도가_발생한다() {
        paymentClient.setChaosMode("DEAD");

        io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.of("cnpe-bad-cb-" + UUID.randomUUID(),
                        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                                .failureRateThreshold(50)
                                .minimumNumberOfCalls(5)
                                .slidingWindowSize(5)
                                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                                .build());
        TestLogger.attach(cb);

        Retry retry = Retry.of("cnpe-bad-retry-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class,
                        io.github.resilience4j.circuitbreaker.CallNotPermittedException.class) // 잘못된 설정!
                .build());
        TestLogger.attach(retry);

        // CB를 OPEN으로 만듦
        for (int i = 0; i < 5; i++) {
            String key = "pk_cnpe_bad_" + i;
            Supplier<Map<String, Object>> cbDecorated =
                    io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(cb,
                            () -> paymentClient.confirm(key, "order_cnpe_bad", 10000));
            try { cbDecorated.get(); } catch (Exception ignored) {}
        }
        assertThat(cb.getState()).isEqualTo(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);

        // Retry(바깥) → CB(안쪽), CallNotPermittedException을 retryExceptions에 포함
        AtomicInteger cbNotPermittedCount = new AtomicInteger(0);
        Supplier<Map<String, Object>> cbDecorated =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(cb, () -> {
                    return paymentClient.confirm("pk_cnpe_bad_retry", "order_cnpe_bad_retry", 10000);
                });
        Supplier<Map<String, Object>> retryDecorated = Retry.decorateSupplier(retry, cbDecorated);

        try {
            retryDecorated.get();
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // 3회 재시도 후 최종 CallNotPermittedException
        }

        // CB notPermitted가 3건 (무의미한 재시도 3회)
        assertThat(cb.getMetrics().getNumberOfNotPermittedCalls()).isGreaterThanOrEqualTo(3);
        // Retry가 재시도를 시도함 (재시도 후 실패)
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }
}
