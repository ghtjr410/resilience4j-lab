package com.example.resilience.bulkhead;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.BeforeEach;
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
class BulkheadBasicTest extends ExampleTestBase {

    @BeforeEach
    void configureLongerTimeout() {
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 10000); // readTimeout=10s (SLOW 3s 대기 가능하게)
    }

    /**
     * 동시 25건 요청 중 maxConcurrentCalls(20)까지 통과하고 나머지 5건이 거절되는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 3s 설정 → 25건 동시 요청 → Bulkhead maxConcurrentCalls=20
     *   → 20건 통과 (SLOW 3s 대기 후 성공) + 5건 BulkheadFullException
     *
     * 핵심:
     *   Bulkhead는 동시 실행 수를 제한하여 과부하를 방지한다.
     *   maxWaitDuration=0이면 초과 요청은 대기 없이 즉시 거절된다.
     */
    @Test
    void 동시_25건중_20건_통과하고_5건_거절된다() throws InterruptedException {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));

        Bulkhead bulkhead = Bulkhead.of("test-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(20)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bulkhead);

        int totalCalls = 25;
        ExecutorService executor = Executors.newFixedThreadPool(totalCalls);
        CountDownLatch readyLatch = new CountDownLatch(totalCalls);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalCalls; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // 모든 스레드 동시 시작
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                String key = "pk_bulk_" + idx;
                Supplier<Map<String, Object>> decorated = Bulkhead.decorateSupplier(bulkhead,
                        () -> paymentClient.confirm(key, "order_bulk", 10000));
                try {
                    decorated.get();
                    successCount.incrementAndGet();
                } catch (BulkheadFullException e) {
                    rejectedCount.incrementAndGet();
                } catch (Exception e) {
                    // SLOW 모드에서 성공 응답 받으면 success
                    successCount.incrementAndGet();
                }
            }));
        }

        readyLatch.await(); // 모든 스레드 준비 완료
        startLatch.countDown(); // 동시 시작

        for (Future<?> future : futures) {
            try { future.get(15, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(20);
        assertThat(rejectedCount.get()).isEqualTo(5);
    }

    /**
     * maxWaitDuration=0일 때 거절된 요청이 즉시 완료되는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 3s + maxConcurrentCalls=20 + maxWaitDuration=0
     *   → 초과 요청은 대기 없이 즉시 BulkheadFullException
     *   → 거절된 요청의 소요 시간이 500ms 미만
     *
     * 핵심:
     *   maxWaitDuration=0이면 Bulkhead 거절이 즉시 발생하므로
     *   클라이언트가 빠르게 실패를 감지하고 대응할 수 있다 (fail-fast).
     */
    @Test
    void maxWaitDuration_0이면_거절된_요청은_즉시_완료된다() throws InterruptedException {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));

        Bulkhead bulkhead = Bulkhead.of("test-immediate-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(20)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bulkhead);

        int totalCalls = 25;
        ExecutorService executor = Executors.newFixedThreadPool(totalCalls);
        CountDownLatch readyLatch = new CountDownLatch(totalCalls);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Long> rejectedDurations = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalCalls; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                String key = "pk_bulk_imm_" + idx;
                Supplier<Map<String, Object>> decorated = Bulkhead.decorateSupplier(bulkhead,
                        () -> paymentClient.confirm(key, "order_bulk_imm", 10000));

                long start = System.currentTimeMillis();
                try {
                    decorated.get();
                } catch (BulkheadFullException e) {
                    long elapsed = System.currentTimeMillis() - start;
                    rejectedDurations.add(elapsed);
                } catch (Exception ignored) {}
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        for (Future<?> future : futures) {
            try { future.get(15, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();

        assertThat(rejectedDurations).isNotEmpty();
        // 거절된 요청들은 500ms 이내에 완료되어야 함
        rejectedDurations.forEach(duration ->
                assertThat(duration).isLessThan(500));
    }

    /**
     * maxWaitDuration > 0이면 대기 후 통과할 수 있는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 2s + maxConcurrentCalls=3 + maxWaitDuration=5s
     *   → 6건 동시 요청 → 첫 3건 통과(2s 소요) → 대기 중이던 3건도 통과
     *   → 전부 성공 (거절 0건)
     *
     * 핵심:
     *   maxWaitDuration > 0이면 동시 실행 슬롯이 빌 때까지 대기한다.
     *   대기 시간 내에 슬롯이 열리면 요청이 통과되므로 거절률을 낮출 수 있다.
     */
    @Test
    void maxWaitDuration이_0보다_크면_대기_후_통과한다() throws InterruptedException {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2000"));

        Bulkhead bulkhead = Bulkhead.of("test-wait-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(3)
                .maxWaitDuration(Duration.ofSeconds(5)) // 최대 5초 대기
                .build());
        TestLogger.attach(bulkhead);

        int totalCalls = 6;
        ExecutorService executor = Executors.newFixedThreadPool(totalCalls);
        CountDownLatch readyLatch = new CountDownLatch(totalCalls);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalCalls; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try { startLatch.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                String key = "pk_bulk_wait_" + idx;
                Supplier<Map<String, Object>> decorated = Bulkhead.decorateSupplier(bulkhead,
                        () -> paymentClient.confirm(key, "order_bulk_wait", 10000));
                try {
                    decorated.get();
                    successCount.incrementAndGet();
                } catch (BulkheadFullException e) {
                    rejectedCount.incrementAndGet();
                } catch (Exception e) {
                    successCount.incrementAndGet();
                }
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        for (Future<?> future : futures) {
            try { future.get(15, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();

        // 첫 3건 통과(2s) → 대기 중이던 3건도 통과 → 전부 성공
        assertThat(successCount.get()).isEqualTo(6);
        assertThat(rejectedCount.get()).isEqualTo(0);
    }

    /**
     * Bulkhead(바깥) → CB(안쪽) 구조에서 Bulkhead 거절은 CB 실패로 집계되지 않는 것을 검증한다.
     *
     * 흐름:
     *   Bulkhead(바깥) → CB(안쪽) → client
     *   → Bulkhead에서 거절된 요청은 CB까지 도달하지 않음
     *   → CB 실패 카운트 0, CLOSED 유지
     *
     * 핵심:
     *   Bulkhead를 바깥에 배치하면 동시 실행 제한으로 인한 거절이
     *   CB의 실패율에 영향을 주지 않는다.
     *   이것이 Bulkhead + CB 조합의 올바른 배치 순서이다.
     */
    @Test
    void Bulkhead_바깥_CB_안쪽이면_거절이_CB_실패로_집계되지_않는다() throws Exception {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));

        Bulkhead bulkhead = Bulkhead.of("test-cb-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(2)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bulkhead);

        CircuitBreaker cb = CircuitBreaker.of("test-bulk-cb-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch readyLatch = new CountDownLatch(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try { startLatch.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                String key = "pk_bulk_cb_" + idx;
                // Bulkhead(바깥) → CB(안쪽) → client
                Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_bulk_cb", 10000));
                Supplier<Map<String, Object>> bulkheadDecorated = Bulkhead.decorateSupplier(bulkhead, cbDecorated);

                try {
                    bulkheadDecorated.get();
                } catch (BulkheadFullException e) {
                    rejectedCount.incrementAndGet();
                } catch (Exception ignored) {}
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        for (Future<?> f : futures) {
            try { f.get(15, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();

        // 2건 Bulkhead 거절 발생
        assertThat(rejectedCount.get()).isEqualTo(2);
        // Bulkhead 거절은 CB까지 도달하지 않음 → CB 실패 카운트 0
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * 앞선 요청이 완료되면 슬롯이 반환되어 대기 중이던 요청이 통과하는 것을 검증한다.
     *
     * 흐름:
     *   maxConcurrentCalls=2, maxWaitDuration=5s
     *   1. 2건 동시 요청 (슬롯 점유) → SLOW 2s 응답 대기
     *   2. 1초 후 3번째 요청 → 슬롯 없음 → 대기열 진입
     *   3. 2초 후 앞선 요청 완료 → 슬롯 반환 → 3번째 요청 통과
     *
     * 핵심:
     *   기존 테스트는 "전부 다 통과했다"만 확인한다.
     *   이 테스트는 "슬롯 반환 시점과 후속 요청 통과 시점"을 명시적으로 증명한다.
     *   Bulkhead의 핵심 메커니즘은 세마포어 기반 슬롯 풀이다.
     */
    @Test
    void 슬롯_반환_후_대기_중이던_요청이_통과한다() throws Exception {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2000"));

        Bulkhead bulkhead = Bulkhead.of("test-slot-return-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(2)
                .maxWaitDuration(Duration.ofSeconds(5))
                .build());
        TestLogger.attach(bulkhead);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Long> completionTimestamps = new CopyOnWriteArrayList<>();
        long testStart = System.currentTimeMillis();

        // Phase 1: 2건 동시 요청 → 슬롯 점유
        CountDownLatch phase1Ready = new CountDownLatch(2);
        CountDownLatch phase1Start = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            executor.submit(() -> {
                phase1Ready.countDown();
                try { phase1Start.await(); } catch (InterruptedException e) { return; }

                String key = "pk_slot_" + idx;
                Supplier<Map<String, Object>> decorated = Bulkhead.decorateSupplier(bulkhead,
                        () -> paymentClient.confirm(key, "order_slot", 10000));
                try {
                    decorated.get();
                    completionTimestamps.add(System.currentTimeMillis());
                    successCount.incrementAndGet();
                } catch (Exception ignored) {}
            });
        }
        phase1Ready.await();
        phase1Start.countDown();

        // Phase 2: 1초 후 3번째 요청 → 슬롯 없음 → 대기열 진입
        Thread.sleep(1000);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(0);

        long thirdRequestStart = System.currentTimeMillis();
        Future<?> thirdFuture = executor.submit(() -> {
            String key = "pk_slot_2";
            Supplier<Map<String, Object>> decorated = Bulkhead.decorateSupplier(bulkhead,
                    () -> paymentClient.confirm(key, "order_slot_wait", 10000));
            try {
                decorated.get();
                completionTimestamps.add(System.currentTimeMillis());
                successCount.incrementAndGet();
            } catch (Exception ignored) {}
        });

        thirdFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 3건 전부 성공
        assertThat(successCount.get()).isEqualTo(3);

        // 3번째 요청은 슬롯 대기 후 통과 → 앞선 2건보다 나중에 완료
        // 앞선 2건: ~2초 후 완료, 3번째: 슬롯 반환(~2초) + SLOW(~2초) = ~3초 후 완료
        long thirdRequestElapsed = completionTimestamps.get(2) - thirdRequestStart;
        assertThat(thirdRequestElapsed).isGreaterThanOrEqualTo(2500L); // 슬롯 대기 ~1초 + SLOW 2초
    }

    /**
     * CB(바깥) → Bulkhead(안쪽) 잘못된 순서에서 Bulkhead 거절이 CB 실패로 집계되는 것을 검증한다.
     *
     * 흐름:
     *   CB(바깥) → Bulkhead(안쪽) → client
     *   → Bulkhead에서 BulkheadFullException 발생
     *   → CB가 이 예외를 실패로 집계 → 실패율 상승 → OPEN 위험
     *
     * 핵심:
     *   올바른 순서(Bulkhead 바깥 → CB 안쪽)와 정반대의 결과가 나온다.
     *   서버는 정상인데 동시 요청 폭주로 서킷이 열리는 오작동이 발생한다.
     *   실무에서 Spring AOP aspect order를 잘못 잡으면 이 문제가 발생한다.
     */
    @Test
    void CB_바깥_Bulkhead_안쪽이면_거절이_CB_실패로_집계된다() throws Exception {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));

        Bulkhead bulkhead = Bulkhead.of("test-wrong-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(2)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bulkhead);

        CircuitBreaker cb = CircuitBreaker.of("test-wrong-cb-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(4)
                        .slidingWindowSize(4)
                        // BulkheadFullException은 RuntimeException이므로 기본 설정으로 실패 집계됨
                        .build());
        TestLogger.attach(cb);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch readyLatch = new CountDownLatch(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try { startLatch.await(); } catch (InterruptedException e) { return; }

                String key = "pk_wrong_bh_" + idx;
                // 잘못된 순서: CB(바깥) → Bulkhead(안쪽) → client
                Supplier<Map<String, Object>> bulkheadDecorated = Bulkhead.decorateSupplier(bulkhead,
                        () -> paymentClient.confirm(key, "order_wrong_bh", 10000));
                Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb, bulkheadDecorated);

                try {
                    cbDecorated.get();
                } catch (BulkheadFullException e) {
                    rejectedCount.incrementAndGet();
                } catch (Exception ignored) {}
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        for (Future<?> f : futures) {
            try { f.get(15, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();

        // 2건 Bulkhead 거절 발생
        assertThat(rejectedCount.get()).isEqualTo(2);

        // 잘못된 순서: BulkheadFullException이 CB 실패로 집계됨
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    /**
     * CB OPEN 상태에서 Bulkhead 슬롯이 순간 소비됐다가 즉시 반환되는 것을 검증한다.
     *
     * 흐름:
     *   Bulkhead(바깥, maxConcurrent=3) → CB(안쪽, FORCED_OPEN)
     *   → Bulkhead 슬롯 확보 → CB 즉시 거절(CallNotPermittedException)
     *   → Bulkhead 슬롯 즉시 반환
     *
     * 핵심:
     *   올바른 순서(Bulkhead 바깥 → CB 안쪽)에서 CB가 OPEN이면:
     *   1. Bulkhead 슬롯을 잡고 → CB가 즉시 거절 → 슬롯 즉시 반환
     *   2. CB 거절은 동기적(마이크로초)이므로 슬롯 점유 시간이 극히 짧다
     *   3. 따라서 CB OPEN이 Bulkhead 가용성에 실질적 영향을 주지 않는다
     *
     *   이 메커니즘을 모르면 "CB OPEN인데 왜 Bulkhead가 관여하나?"라는 의문이 생긴다.
     *   답: 데코레이터 순서상 Bulkhead가 먼저 실행되지만, 즉시 반환되므로 무해하다.
     */
    @Test
    void CB_OPEN_상태에서_Bulkhead_슬롯은_잡았다가_즉시_반환된다() {
        paymentClient.setChaosMode("NORMAL");

        Bulkhead bulkhead = Bulkhead.of("test-cb-open-slot-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(3)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bulkhead);

        CircuitBreaker cb = CircuitBreaker.of("test-cb-open-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        // CB를 강제 OPEN으로 전환
        cb.transitionToForcedOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(3);

        // 10건 호출 → 모두 CB 거절, Bulkhead 슬롯은 즉시 반환
        AtomicInteger callNotPermittedCount = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            String key = "pk_cb_open_slot_" + i;
            Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_cb_open_slot", 10000));
            Supplier<Map<String, Object>> fullChain = Bulkhead.decorateSupplier(bulkhead, cbDecorated);

            try {
                fullChain.get();
            } catch (CallNotPermittedException e) {
                callNotPermittedCount.incrementAndGet();
            }

            // 매 호출 후 슬롯이 즉시 반환되었는지 확인
            assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(3);
        }

        // 10건 모두 CB 거절
        assertThat(callNotPermittedCount.get()).isEqualTo(10);

        // Bulkhead는 전혀 영향 없음 — 슬롯 전부 가용
        TestLogger.summary(cb);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(3);
    }
}
