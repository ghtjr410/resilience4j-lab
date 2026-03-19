package com.example.resilience.combination;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FiveLayerChainTest extends ExampleTestBase {

    private ExecutorService asyncExecutor;

    @BeforeEach
    void setUp() {
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 10000);
        asyncExecutor = Executors.newFixedThreadPool(4);
    }

    /**
     * Retry → CB → RateLimiter → TimeLimiter → Bulkhead 5단 조합에서
     * 정상 요청이 모든 레이어를 통과하는 것을 검증한다.
     *
     * 데코레이터 순서 (바깥 → 안쪽):
     *   Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → client
     *
     * 이는 Resilience4j Spring Boot의 기본 Aspect 순서와 동일하다:
     *   Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( Function ) ) ) ) )
     *
     * 흐름:
     *   NORMAL 모드 → 3건 순차 요청 → 모든 레이어 통과 → 성공
     *   → CB: 성공 3건, Retry: 재시도 0건, Bulkhead: 슬롯 전부 가용
     *
     * 핵심:
     *   5단 조합에서 각 레이어가 서로를 간섭하지 않고 올바르게 동작하는지 확인.
     *   이 순서의 의미:
     *   - Retry(가장 바깥): 최종 실패 시 전체를 재시도
     *   - CB: 실패율 평가 (Retry 내부 재시도는 CB에 1건으로 집계)
     *   - RateLimiter: 초당 호출 수 제한
     *   - TimeLimiter: 개별 호출 타임아웃
     *   - Bulkhead(가장 안쪽): 동시 실행 수 제한
     */
    @Test
    void 풀체인_5단에서_정상_요청이_모든_레이어를_통과한다() throws Exception {
        paymentClient.setChaosMode("NORMAL");

        Retry retry = Retry.of("5l-retry-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        CircuitBreaker cb = CircuitBreaker.of("5l-cb-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        RateLimiter rl = RateLimiter.of("5l-rl-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build());
        TestLogger.attach(rl);

        TimeLimiter tl = TimeLimiter.of("5l-tl-" + UUID.randomUUID(), TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .build());
        TestLogger.attach(tl);

        Bulkhead bh = Bulkhead.of("5l-bh-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(5)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bh);

        // 3건 순차 요청 → 전부 성공
        for (int i = 0; i < 3; i++) {
            final int idx = i;

            // 데코레이터 순서: Retry → CB → RL → TL → BH → client
            // 안쪽부터 감싸기: BH(client) → TL(BH) → RL(TL) → CB(RL) → Retry(CB)
            Supplier<Map<String, Object>> bhDecorated = Bulkhead.decorateSupplier(bh,
                    () -> paymentClient.confirm("pk_5l_" + idx, "order_5l", 10000));

            Supplier<CompletableFuture<Map<String, Object>>> futureSupplier = () ->
                    CompletableFuture.supplyAsync(bhDecorated::get, asyncExecutor);
            Callable<Map<String, Object>> tlDecorated = TimeLimiter.decorateFutureSupplier(tl, futureSupplier);

            Supplier<Map<String, Object>> rlDecorated = RateLimiter.decorateSupplier(rl, () -> {
                try { return tlDecorated.call(); } catch (Exception e) { throw new RuntimeException(e); }
            });
            Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb, rlDecorated);
            Supplier<Map<String, Object>> fullChain = Retry.decorateSupplier(retry, cbDecorated);

            Map<String, Object> result = fullChain.get();
            assertThat(result).isNotNull();
        }

        // 모든 레이어 정상
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(3);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(bh.getMetrics().getAvailableConcurrentCalls()).isEqualTo(5);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt()).isEqualTo(3);
    }

    /**
     * 5단 풀체인에서 RateLimiter 거절 시 CB·Retry에 미치는 영향을 검증한다.
     *
     * 흐름:
     *   limitForPeriod=2 → 2건 성공 → 3번째 RateLimiter 거절
     *   → RequestNotPermitted는 retryExceptions에 없음 → Retry 재시도 안 함
     *   → CB에도 RequestNotPermitted 집계 (RuntimeException이므로)
     *
     * 핵심:
     *   5단 순서에서 RateLimiter는 CB 안쪽이다.
     *   따라서 RequestNotPermitted가 CB를 관통하여 실패로 집계된다.
     *   이를 방지하려면 CB의 ignoreExceptions에 RequestNotPermitted를 추가하거나,
     *   RateLimiter를 CB 바깥에 배치해야 한다.
     *
     *   기본 Aspect 순서의 함정: RateLimiter가 CB 안쪽이면 rate limit 거절이 CB를 오염시킨다.
     */
    @Test
    void 풀체인_5단에서_RateLimiter_거절이_CB를_오염시키는_함정을_증명한다() {
        paymentClient.setChaosMode("NORMAL");

        Retry retry = Retry.of("5l-rl-trap-retry-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(1) // 재시도 없음
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        CircuitBreaker cb = CircuitBreaker.of("5l-rl-trap-cb-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(4)
                        .slidingWindowSize(4)
                        // 기본 설정: 모든 예외를 실패로 집계 (RequestNotPermitted 포함)
                        .build());
        TestLogger.attach(cb);

        RateLimiter rl = RateLimiter.of("5l-rl-trap-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build());
        TestLogger.attach(rl);

        Bulkhead bh = Bulkhead.of("5l-rl-trap-bh-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ZERO)
                .build());
        TestLogger.attach(bh);

        AtomicInteger rlRejected = new AtomicInteger(0);

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            // 기본 순서: Retry → CB → RL → BH → client
            Supplier<Map<String, Object>> bhDecorated = Bulkhead.decorateSupplier(bh,
                    () -> paymentClient.confirm("pk_5l_rl_" + idx, "order_5l_rl", 10000));
            Supplier<Map<String, Object>> rlDecorated = RateLimiter.decorateSupplier(rl, bhDecorated);
            Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb, rlDecorated);
            Supplier<Map<String, Object>> fullChain = Retry.decorateSupplier(retry, cbDecorated);

            try {
                fullChain.get();
            } catch (RequestNotPermitted e) {
                rlRejected.incrementAndGet();
            } catch (Exception ignored) {}
        }

        // 2건 RL 거절
        assertThat(rlRejected.get()).isEqualTo(2);

        // 함정: RL 거절이 CB에 실패로 집계됨
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(2);
    }

    /**
     * 5단 풀체인에서 장애 발생 시 Retry·CB가 올바르게 동작하는 것을 검증한다.
     *
     * 흐름:
     *   DEAD → 5건 요청 → 각 Retry 3회 시도 → 전부 실패
     *   → CB에 5건 실패 집계 → OPEN
     *   → 추가 요청 시 CB 즉시 거절 (하위 레이어 도달 안 함)
     *
     * 핵심:
     *   장애 시 전체 체인의 동작 순서:
     *   1. Retry가 3회 시도 (내부에서 소화)
     *   2. CB에 최종 실패 1건 집계
     *   3. CB OPEN 후 → Retry가 재시도해도 CB에서 즉시 차단
     *   4. Bulkhead 슬롯은 CB 거절 시 즉시 반환
     */
    @Test
    void 풀체인_5단에서_장애시_Retry와_CB가_올바르게_연동한다() {
        paymentClient.setChaosMode("DEAD");

        Retry retry = Retry.of("5l-fail-retry-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(3)
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        CircuitBreaker cb = CircuitBreaker.of("5l-fail-cb-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        RateLimiter rl = RateLimiter.of("5l-fail-rl-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build());

        Bulkhead bh = Bulkhead.of("5l-fail-bh-" + UUID.randomUUID(), BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ZERO)
                .build());

        // Phase 1: 5건 실패 → CB OPEN
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            Supplier<Map<String, Object>> bhDecorated = Bulkhead.decorateSupplier(bh,
                    () -> paymentClient.confirm("pk_5l_f_" + idx, "order_5l_f", 10000));
            Supplier<Map<String, Object>> rlDecorated = RateLimiter.decorateSupplier(rl, bhDecorated);
            Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb, rlDecorated);
            Supplier<Map<String, Object>> fullChain = Retry.decorateSupplier(retry, cbDecorated);

            try { fullChain.get(); } catch (Exception ignored) {}
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);

        // Phase 2: CB OPEN → 즉시 거절, Bulkhead 슬롯 정상
        AtomicInteger notPermitted = new AtomicInteger(0);
        for (int i = 0; i < 3; i++) {
            Supplier<Map<String, Object>> bhDecorated = Bulkhead.decorateSupplier(bh,
                    () -> paymentClient.confirm("pk_5l_open", "order_5l_open", 10000));
            Supplier<Map<String, Object>> rlDecorated = RateLimiter.decorateSupplier(rl, bhDecorated);
            Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb, rlDecorated);
            Supplier<Map<String, Object>> fullChain = Retry.decorateSupplier(retry, cbDecorated);

            try { fullChain.get(); } catch (CallNotPermittedException e) {
                notPermitted.incrementAndGet();
            } catch (Exception ignored) {}
        }

        TestLogger.summary(cb);
        assertThat(notPermitted.get()).isEqualTo(3);
        assertThat(bh.getMetrics().getAvailableConcurrentCalls()).isEqualTo(10);
    }
}
