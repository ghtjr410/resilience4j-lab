package com.example.resilience.bulkhead;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ThreadPoolBulkheadTest extends ExampleTestBase {

    @BeforeEach
    void configureLongerTimeout() {
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 10000);
    }

    /**
     * ThreadPoolBulkhead의 maxThreadPoolSize + queueCapacity 초과 시 거절되는 것을 검증한다.
     *
     * 흐름:
     *   maxThreadPoolSize=2, coreThreadPoolSize=2, queueCapacity=2
     *   → SLOW 3s → 6건 동시 요청
     *   → 2건: 스레드풀에서 즉시 실행 (슬롯 점유)
     *   → 2건: 큐에서 대기
     *   → 2건: 스레드풀 + 큐 모두 가득 → BulkheadFullException
     *
     * 핵심:
     *   SemaphoreBulkhead는 호출 스레드에서 실행하고 동시 실행 수만 제한한다.
     *   ThreadPoolBulkhead는 별도 스레드풀에서 실행하며 "스레드 수 + 큐 크기"로 제한한다.
     *
     *   차이점:
     *   - SemaphoreBulkhead: 호출 스레드 그대로 사용, maxConcurrentCalls만 제한
     *   - ThreadPoolBulkhead: 전용 스레드풀 사용, maxThreadPoolSize + queueCapacity로 제한
     *   - ThreadPoolBulkhead는 CompletionStage를 반환 (비동기)
     */
    @Test
    void maxThreadPoolSize와_queueCapacity_초과시_거절된다() throws Exception {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));

        ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.of("tp-basic-" + UUID.randomUUID(),
                ThreadPoolBulkheadConfig.custom()
                        .maxThreadPoolSize(2)
                        .coreThreadPoolSize(2)
                        .queueCapacity(2) // 스레드 2 + 큐 2 = 최대 4건
                        .build());

        int totalCalls = 6;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalCalls);

        for (int i = 0; i < totalCalls; i++) {
            final int idx = i;
            try {
                CompletionStage<Map<String, Object>> stage = bulkhead.executeSupplier(
                        () -> paymentClient.confirm("pk_tp_" + idx, "order_tp", 10000));
                stage.whenComplete((result, ex) -> {
                    if (ex == null) {
                        successCount.incrementAndGet();
                    }
                    latch.countDown();
                });
            } catch (BulkheadFullException e) {
                rejectedCount.incrementAndGet();
                latch.countDown();
            }
        }

        latch.await(15, TimeUnit.SECONDS);

        // 스레드 2 + 큐 2 = 4건 통과, 2건 거절
        assertThat(successCount.get()).isEqualTo(4);
        assertThat(rejectedCount.get()).isEqualTo(2);

        bulkhead.close();
    }

    /**
     * queueCapacity=0이면 스레드풀이 가득 찬 즉시 거절되는 것을 검증한다.
     *
     * 흐름:
     *   maxThreadPoolSize=2, queueCapacity=0
     *   → SLOW 3s → 4건 동시 요청
     *   → 2건: 스레드풀에서 실행
     *   → 2건: 큐 없음 → 즉시 BulkheadFullException
     *
     * 핵심:
     *   queueCapacity=0은 SemaphoreBulkhead의 maxWaitDuration=0과 유사한 fail-fast 동작이다.
     *   큐를 사용하지 않으면 거절이 빠르지만, 버스트 트래픽에 취약하다.
     */
    @Test
    void queueCapacity_0이면_스레드풀_가득시_즉시_거절된다() throws Exception {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));

        ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.of("tp-no-queue-" + UUID.randomUUID(),
                ThreadPoolBulkheadConfig.custom()
                        .maxThreadPoolSize(2)
                        .coreThreadPoolSize(2)
                        .queueCapacity(0) // 큐 없음
                        .build());

        int totalCalls = 4;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalCalls);

        for (int i = 0; i < totalCalls; i++) {
            final int idx = i;
            try {
                CompletionStage<Map<String, Object>> stage = bulkhead.executeSupplier(
                        () -> paymentClient.confirm("pk_tp_nq_" + idx, "order_tp_nq", 10000));
                stage.whenComplete((result, ex) -> {
                    if (ex == null) {
                        successCount.incrementAndGet();
                    }
                    latch.countDown();
                });
            } catch (BulkheadFullException e) {
                rejectedCount.incrementAndGet();
                latch.countDown();
            }
        }

        latch.await(15, TimeUnit.SECONDS);

        // 스레드 2건만 통과, 2건 즉시 거절
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(rejectedCount.get()).isEqualTo(2);

        bulkhead.close();
    }

    /**
     * ThreadPoolBulkhead에서 큐에 대기 중이던 요청이 스레드 반환 후 실행되는 것을 검증한다.
     *
     * 흐름:
     *   maxThreadPoolSize=2, queueCapacity=2
     *   → SLOW 2s → 4건 요청
     *   → 2건: 스레드풀에서 실행 (2초 소요)
     *   → 2건: 큐에서 대기 → 2초 후 스레드 반환 → 큐의 2건 실행
     *   → 전부 성공
     *
     * 핵심:
     *   SemaphoreBulkhead의 maxWaitDuration > 0과 유사하지만 메커니즘이 다르다.
     *   SemaphoreBulkhead: 세마포어 acquire 대기
     *   ThreadPoolBulkhead: BlockingQueue에서 대기 → 스레드가 비면 자동 실행
     */
    @Test
    void 큐_대기_중이던_요청이_스레드_반환_후_실행된다() throws Exception {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2000"));

        ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.of("tp-queue-" + UUID.randomUUID(),
                ThreadPoolBulkheadConfig.custom()
                        .maxThreadPoolSize(2)
                        .coreThreadPoolSize(2)
                        .queueCapacity(2)
                        .build());

        int totalCalls = 4;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalCalls);
        List<Long> completionTimestamps = new CopyOnWriteArrayList<>();
        long testStart = System.currentTimeMillis();

        for (int i = 0; i < totalCalls; i++) {
            final int idx = i;
            CompletionStage<Map<String, Object>> stage = bulkhead.executeSupplier(
                    () -> paymentClient.confirm("pk_tp_q_" + idx, "order_tp_q", 10000));
            stage.whenComplete((result, ex) -> {
                if (ex == null) {
                    completionTimestamps.add(System.currentTimeMillis());
                    successCount.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await(15, TimeUnit.SECONDS);

        // 4건 전부 성공
        assertThat(successCount.get()).isEqualTo(4);

        // 첫 2건: ~2초 후 완료, 큐 대기 2건: ~4초 후 완료
        completionTimestamps.sort(Long::compareTo);
        long firstBatchElapsed = completionTimestamps.get(1) - testStart;
        long secondBatchElapsed = completionTimestamps.get(3) - testStart;

        assertThat(firstBatchElapsed).isBetween(1500L, 3500L); // ~2초
        assertThat(secondBatchElapsed).isGreaterThanOrEqualTo(3500L); // ~4초 (대기 + 실행)

        bulkhead.close();
    }

    /**
     * SemaphoreBulkhead와 ThreadPoolBulkhead의 실행 스레드 차이를 검증한다.
     *
     * 흐름:
     *   ThreadPoolBulkhead: 전용 스레드풀에서 실행 → Thread.currentThread().getName()이 다름
     *
     * 핵심:
     *   SemaphoreBulkhead는 호출자의 스레드에서 실행되므로 ThreadLocal이 유지된다.
     *   ThreadPoolBulkhead는 별도 스레드에서 실행되므로 ThreadLocal이 유실된다.
     *   이 차이가 contextPropagator가 필요한 근본 이유이다.
     */
    @Test
    void ThreadPoolBulkhead는_전용_스레드풀에서_실행된다() throws Exception {
        paymentClient.setChaosMode("NORMAL");

        ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.of("tp-thread-" + UUID.randomUUID(),
                ThreadPoolBulkheadConfig.custom()
                        .maxThreadPoolSize(1)
                        .coreThreadPoolSize(1)
                        .queueCapacity(1)
                        .build());

        String callerThread = Thread.currentThread().getName();

        CompletionStage<String> stage = bulkhead.executeCallable(() -> {
            paymentClient.confirm("pk_tp_thread", "order_tp_thread", 10000);
            return Thread.currentThread().getName();
        });

        String executionThread = stage.toCompletableFuture().get(10, TimeUnit.SECONDS);

        // 호출 스레드와 실행 스레드가 다름
        assertThat(executionThread).isNotEqualTo(callerThread);
        // ThreadPoolBulkhead 전용 스레드풀 이름 포함
        assertThat(executionThread).contains("bulkhead-tp-thread");

        bulkhead.close();
    }

    /**
     * ThreadPoolBulkhead + CB 조합에서 거절이 CB 실패에 영향을 주지 않는 것을 검증한다.
     *
     * 흐름:
     *   ThreadPoolBulkhead(바깥) → CB(안쪽)
     *   → 스레드풀+큐 초과 → BulkheadFullException (동기적, CB 도달 전)
     *   → CB 실패 카운트 0
     *
     * 핵심:
     *   SemaphoreBulkhead + CB 조합과 동일한 원리.
     *   ThreadPoolBulkhead를 바깥에 배치하면 거절이 CB에 영향을 주지 않는다.
     *   단, ThreadPoolBulkhead 거절은 동기적(executeSupplier 호출 시점)이므로
     *   SemaphoreBulkhead와 동일하게 CB 도달 전에 발생한다.
     */
    @Test
    void ThreadPoolBulkhead_바깥_CB_안쪽이면_거절이_CB에_영향주지_않는다() throws Exception {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));

        ThreadPoolBulkhead bulkhead = ThreadPoolBulkhead.of("tp-cb-" + UUID.randomUUID(),
                ThreadPoolBulkheadConfig.custom()
                        .maxThreadPoolSize(2)
                        .coreThreadPoolSize(2)
                        .queueCapacity(0)
                        .build());

        CircuitBreaker cb = CircuitBreaker.of("tp-cb-inner-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        AtomicInteger rejectedCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(4);

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            try {
                CompletionStage<Map<String, Object>> stage = bulkhead.executeCallable(
                        () -> cb.executeSupplier(
                                () -> paymentClient.confirm("pk_tp_cb_" + idx, "order_tp_cb", 10000)));
                stage.whenComplete((result, ex) -> latch.countDown());
            } catch (BulkheadFullException e) {
                rejectedCount.incrementAndGet();
                latch.countDown();
            }
        }

        latch.await(15, TimeUnit.SECONDS);

        // 2건 거절 (스레드 2, 큐 0)
        assertThat(rejectedCount.get()).isEqualTo(2);

        // 거절은 CB 도달 전 → CB 실패 0건
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        bulkhead.close();
    }
}
