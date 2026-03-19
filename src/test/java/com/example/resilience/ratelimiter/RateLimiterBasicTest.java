package com.example.resilience.ratelimiter;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RateLimiterBasicTest extends ExampleTestBase {

    /**
     * limitForPeriod 초과 시 RequestNotPermitted가 발생하는 것을 검증한다.
     *
     * 흐름:
     *   limitForPeriod=3, limitRefreshPeriod=10s, timeoutDuration=0
     *   → 3건 성공 → 4번째 즉시 RequestNotPermitted
     *
     * 핵심:
     *   RateLimiter는 일정 시간 동안 허용 가능한 호출 수를 제한한다.
     *   timeoutDuration=0이면 허용량 초과 시 대기 없이 즉시 거절한다 (fail-fast).
     */
    @Test
    void limitForPeriod_초과시_RequestNotPermitted_발생한다() {
        paymentClient.setChaosMode("NORMAL");

        RateLimiter rateLimiter = RateLimiter.of("rl-basic-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(3)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO) // 즉시 거절
                .build());
        TestLogger.attach(rateLimiter);

        // 3건 성공
        for (int i = 0; i < 3; i++) {
            String key = "pk_rl_" + i;
            Supplier<Map<String, Object>> decorated = RateLimiter.decorateSupplier(rateLimiter,
                    () -> paymentClient.confirm(key, "order_rl", 10000));
            Map<String, Object> result = decorated.get();
            assertThat(result).isNotNull();
        }

        // 4번째 즉시 거절
        Supplier<Map<String, Object>> decorated = RateLimiter.decorateSupplier(rateLimiter,
                () -> paymentClient.confirm("pk_rl_over", "order_rl", 10000));
        assertThatThrownBy(decorated::get).isInstanceOf(RequestNotPermitted.class);
    }

    /**
     * timeoutDuration > 0이면 허용량 갱신까지 대기 후 통과하는 것을 검증한다.
     *
     * 흐름:
     *   limitForPeriod=2, limitRefreshPeriod=2s, timeoutDuration=3s
     *   → 2건 소진 → 3번째 요청 대기 → 2초 후 허용량 갱신 → 통과
     *   → 전체 소요시간 >= 2s (갱신 대기)
     *
     * 핵심:
     *   timeoutDuration > 0이면 Bulkhead의 maxWaitDuration과 유사한 대기 메커니즘이 동작한다.
     *   허용량이 갱신되면 대기 중이던 요청이 통과한다.
     */
    @Test
    void timeoutDuration이_0보다_크면_허용량_갱신까지_대기_후_통과한다() {
        paymentClient.setChaosMode("NORMAL");

        RateLimiter rateLimiter = RateLimiter.of("rl-wait-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofSeconds(2))
                .timeoutDuration(Duration.ofSeconds(3)) // 최대 3초 대기
                .build());
        TestLogger.attach(rateLimiter);

        // 2건 소진
        for (int i = 0; i < 2; i++) {
            String key = "pk_rl_wait_" + i;
            Supplier<Map<String, Object>> decorated = RateLimiter.decorateSupplier(rateLimiter,
                    () -> paymentClient.confirm(key, "order_rl_wait", 10000));
            decorated.get();
        }

        // 3번째 요청 → 허용량 갱신 대기 → 통과
        long start = System.currentTimeMillis();
        Supplier<Map<String, Object>> decorated = RateLimiter.decorateSupplier(rateLimiter,
                () -> paymentClient.confirm("pk_rl_wait_2", "order_rl_wait", 10000));
        Map<String, Object> result = decorated.get();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isNotNull();
        assertThat(elapsed).isGreaterThanOrEqualTo(1500L); // 갱신 대기 ~2s
    }

    /**
     * timeoutDuration=0이면 허용량 초과 시 즉시 거절되는 것을 검증한다.
     *
     * 흐름:
     *   limitForPeriod=2, timeoutDuration=0 → 2건 소진 → 3번째 즉시 거절
     *   → 거절 소요시간 < 100ms
     *
     * 핵심:
     *   timeoutDuration=0은 fail-fast 동작을 보장한다.
     *   Bulkhead의 maxWaitDuration=0과 동일한 철학이다.
     */
    @Test
    void timeoutDuration_0이면_즉시_거절된다() {
        paymentClient.setChaosMode("NORMAL");

        RateLimiter rateLimiter = RateLimiter.of("rl-immediate-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build());
        TestLogger.attach(rateLimiter);

        // 2건 소진
        for (int i = 0; i < 2; i++) {
            String key = "pk_rl_imm_" + i;
            RateLimiter.decorateSupplier(rateLimiter,
                    () -> paymentClient.confirm(key, "order_rl_imm", 10000)).get();
        }

        // 3번째 즉시 거절
        long start = System.currentTimeMillis();
        Supplier<Map<String, Object>> decorated = RateLimiter.decorateSupplier(rateLimiter,
                () -> paymentClient.confirm("pk_rl_imm_over", "order_rl_imm", 10000));
        try {
            decorated.get();
        } catch (RequestNotPermitted e) {
            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed).isLessThan(100);
        }
    }

    /**
     * limitRefreshPeriod 경과 후 허용량이 갱신되는 것을 검증한다.
     *
     * 흐름:
     *   limitForPeriod=2, limitRefreshPeriod=2s
     *   → 2건 소진 → 2초 대기 → 허용량 갱신 → 다시 2건 성공
     *
     * 핵심:
     *   RateLimiter는 limitRefreshPeriod마다 허용량을 초기값으로 리셋한다.
     *   이 주기적 리셋이 RateLimiter의 핵심 메커니즘이다.
     */
    @Test
    void limitRefreshPeriod_경과_후_허용량이_갱신된다() throws InterruptedException {
        paymentClient.setChaosMode("NORMAL");

        RateLimiter rateLimiter = RateLimiter.of("rl-refresh-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofSeconds(2))
                .timeoutDuration(Duration.ZERO)
                .build());
        TestLogger.attach(rateLimiter);

        // 첫 주기: 2건 성공
        for (int i = 0; i < 2; i++) {
            String key = "pk_rl_ref_" + i;
            RateLimiter.decorateSupplier(rateLimiter,
                    () -> paymentClient.confirm(key, "order_rl_ref", 10000)).get();
        }

        // 허용량 소진 확인
        Supplier<Map<String, Object>> overDecorated = RateLimiter.decorateSupplier(rateLimiter,
                () -> paymentClient.confirm("pk_rl_ref_over", "order_rl_ref", 10000));
        assertThatThrownBy(overDecorated::get).isInstanceOf(RequestNotPermitted.class);

        // 갱신 대기
        Thread.sleep(2500);

        // 두 번째 주기: 다시 2건 성공
        for (int i = 0; i < 2; i++) {
            String key = "pk_rl_ref2_" + i;
            Map<String, Object> result = RateLimiter.decorateSupplier(rateLimiter,
                    () -> paymentClient.confirm(key, "order_rl_ref2", 10000)).get();
            assertThat(result).isNotNull();
        }
    }

    /**
     * 여러 스레드가 동시에 경쟁할 때 정확히 limitForPeriod만 통과하는 것을 검증한다.
     *
     * 흐름:
     *   limitForPeriod=5, timeoutDuration=0 → 10건 동시 요청
     *   → 정확히 5건 통과, 5건 거절
     *
     * 핵심:
     *   RateLimiter는 내부적으로 AtomicInteger 기반으로 동시성을 보장한다.
     *   여러 스레드가 동시에 요청해도 limitForPeriod를 초과하지 않는다.
     */
    @Test
    void 동시_요청에서_정확히_limitForPeriod만_통과한다() throws InterruptedException {
        paymentClient.setChaosMode("NORMAL");

        RateLimiter rateLimiter = RateLimiter.of("rl-concurrent-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(5)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build());
        TestLogger.attach(rateLimiter);

        int totalCalls = 10;
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

                String key = "pk_rl_con_" + idx;
                Supplier<Map<String, Object>> decorated = RateLimiter.decorateSupplier(rateLimiter,
                        () -> paymentClient.confirm(key, "order_rl_con", 10000));
                try {
                    decorated.get();
                    successCount.incrementAndGet();
                } catch (RequestNotPermitted e) {
                    rejectedCount.incrementAndGet();
                }
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        for (Future<?> future : futures) {
            try { future.get(15, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(5);
        assertThat(rejectedCount.get()).isEqualTo(5);
    }

    /**
     * RateLimiter(바깥) → CB(안쪽) 구조에서 rate limit 거절이 CB 실패로 집계되지 않는 것을 검증한다.
     *
     * 흐름:
     *   RateLimiter(바깥, limitForPeriod=2) → CB(안쪽)
     *   → 2건 통과(성공) → 3번째 RateLimiter 거절 → CB까지 도달하지 않음
     *   → CB 성공 2건, 실패 0건, CLOSED 유지
     *
     * 핵심:
     *   Bulkhead + CB 조합과 동일한 원리.
     *   RateLimiter를 바깥에 배치하면 rate limit 거절이 CB 실패율에 영향을 주지 않는다.
     *   이것이 RateLimiter + CB 조합의 올바른 배치 순서이다.
     */
    @Test
    void RateLimiter_바깥_CB_안쪽이면_거절이_CB_실패로_집계되지_않는다() {
        paymentClient.setChaosMode("NORMAL");

        RateLimiter rateLimiter = RateLimiter.of("rl-cb-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build());
        TestLogger.attach(rateLimiter);

        CircuitBreaker cb = CircuitBreaker.of("rl-cb-inner-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(5)
                        .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                        .build());
        TestLogger.attach(cb);

        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < 4; i++) {
            String key = "pk_rl_cb_" + i;
            // RateLimiter(바깥) → CB(안쪽)
            Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb,
                    () -> paymentClient.confirm(key, "order_rl_cb", 10000));
            Supplier<Map<String, Object>> fullChain = RateLimiter.decorateSupplier(rateLimiter, cbDecorated);

            try {
                fullChain.get();
            } catch (RequestNotPermitted e) {
                rejectedCount.incrementAndGet();
            }
        }

        // 2건 RateLimiter 거절
        assertThat(rejectedCount.get()).isEqualTo(2);

        // RateLimiter 거절은 CB까지 도달하지 않음 → CB 성공 2건, 실패 0건
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(2);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * CB(바깥) → RateLimiter(안쪽) 잘못된 순서에서 rate limit 거절이 CB 실패로 집계되는 것을 검증한다.
     *
     * 흐름:
     *   CB(바깥) → RateLimiter(안쪽, limitForPeriod=2)
     *   → 2건 성공 → 3,4번째 RequestNotPermitted → CB가 실패로 집계
     *
     * 핵심:
     *   Bulkhead + CB 잘못된 순서 테스트와 동일한 패턴.
     *   서버는 정상인데 rate limit 초과로 서킷이 실패를 집계하는 오작동이 발생한다.
     */
    @Test
    void CB_바깥_RateLimiter_안쪽이면_거절이_CB_실패로_집계된다() {
        paymentClient.setChaosMode("NORMAL");

        RateLimiter rateLimiter = RateLimiter.of("rl-wrong-" + UUID.randomUUID(), RateLimiterConfig.custom()
                .limitForPeriod(2)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build());
        TestLogger.attach(rateLimiter);

        CircuitBreaker cb = CircuitBreaker.of("rl-wrong-cb-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(4)
                        .slidingWindowSize(4)
                        // RequestNotPermitted는 RuntimeException이므로 기본 설정으로 실패 집계됨
                        .build());
        TestLogger.attach(cb);

        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < 4; i++) {
            String key = "pk_rl_wrong_" + i;
            // 잘못된 순서: CB(바깥) → RateLimiter(안쪽)
            Supplier<Map<String, Object>> rlDecorated = RateLimiter.decorateSupplier(rateLimiter,
                    () -> paymentClient.confirm(key, "order_rl_wrong", 10000));
            Supplier<Map<String, Object>> cbDecorated = CircuitBreaker.decorateSupplier(cb, rlDecorated);

            try {
                cbDecorated.get();
            } catch (RequestNotPermitted e) {
                rejectedCount.incrementAndGet();
            }
        }

        // 2건 RateLimiter 거절
        assertThat(rejectedCount.get()).isEqualTo(2);

        // 잘못된 순서: RequestNotPermitted가 CB 실패로 집계됨
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }
}
