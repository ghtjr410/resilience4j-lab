package com.example.resilience.timelimiter;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TimeLimiterBasicTest extends ExampleTestBase {

    private ExecutorService executor;

    @BeforeEach
    void setUpExecutor() {
        executor = Executors.newFixedThreadPool(4);
        // SLOW/TIMEOUT 테스트용 readTimeout을 넉넉하게
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 15000);
    }

    /**
     * timeoutDuration 초과 시 TimeoutException이 발생하는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 5s + TimeLimiter timeoutDuration=2s
     *   → CompletableFuture로 비동기 실행 → 2초 후 TimeoutException 발생
     *
     * 핵심:
     *   TimeLimiter는 CompletableFuture 기반 비동기 호출에서 동작한다.
     *   RestTemplate의 readTimeout과 달리, 비즈니스 레벨에서 타임아웃을 제어한다.
     *   readTimeout은 네트워크 소켓 대기 시간, TimeLimiter는 전체 작업 완료 시간을 제한한다.
     */
    @Test
    void timeoutDuration_초과시_TimeoutException_발생한다() {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "5000", "slowMaxMs", "5000"));

        TimeLimiter timeLimiter = TimeLimiter.of("tl-basic-" + UUID.randomUUID(), TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(2))
                .build());
        TestLogger.attach(timeLimiter);

        Supplier<CompletableFuture<Map<String, Object>>> futureSupplier = () ->
                CompletableFuture.supplyAsync(
                        () -> paymentClient.confirm("pk_tl_timeout", "order_tl", 10000), executor);

        Callable<Map<String, Object>> decorated = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        assertThatThrownBy(decorated::call).isInstanceOf(TimeoutException.class);
    }

    /**
     * 응답이 timeoutDuration 이내이면 정상 성공하는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 1s + TimeLimiter timeoutDuration=3s
     *   → 응답(1s) < 타임아웃(3s) → 정상 성공
     *
     * 핵심:
     *   TimeLimiter는 타임아웃 이내에 완료된 작업은 정상 결과를 반환한다.
     *   TimeoutTest의 "SLOW_응답이_readTimeout_이내이면_성공한다"와 대응되지만,
     *   이쪽은 비동기 CompletableFuture 기반이다.
     */
    @Test
    void 응답이_timeoutDuration_이내이면_성공한다() throws Exception {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "1000", "slowMaxMs", "1000"));

        TimeLimiter timeLimiter = TimeLimiter.of("tl-ok-" + UUID.randomUUID(), TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(3))
                .build());
        TestLogger.attach(timeLimiter);

        Supplier<CompletableFuture<Map<String, Object>>> futureSupplier = () ->
                CompletableFuture.supplyAsync(
                        () -> paymentClient.confirm("pk_tl_ok", "order_tl_ok", 10000), executor);

        Callable<Map<String, Object>> decorated = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);
        Map<String, Object> result = decorated.call();

        assertThat(result).isNotNull();
    }

    /**
     * cancelRunningFuture=true일 때 타임아웃 발생 시 Future가 취소되는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 5s + timeoutDuration=1s + cancelRunningFuture=true
     *   → 1초 후 TimeoutException → Future.cancel(true) 호출됨
     *   → CompletableFuture가 CancellationException으로 완료됨
     *
     * 핵심:
     *   cancelRunningFuture=true(기본값)이면 타임아웃 시 진행 중인 Future를 취소한다.
     *   리소스 해제가 중요한 상황에서 이 설정을 사용한다.
     */
    @Test
    void cancelRunningFuture_true이면_타임아웃시_Future가_취소된다() {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "5000", "slowMaxMs", "5000"));

        TimeLimiter timeLimiter = TimeLimiter.of("tl-cancel-" + UUID.randomUUID(), TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(1))
                .cancelRunningFuture(true)
                .build());
        TestLogger.attach(timeLimiter);

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        AtomicBoolean taskStarted = new AtomicBoolean(false);

        Supplier<CompletableFuture<Map<String, Object>>> futureSupplier = () -> {
            executor.submit(() -> {
                taskStarted.set(true);
                try {
                    Map<String, Object> result = paymentClient.confirm("pk_tl_cancel", "order_tl_cancel", 10000);
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future;
        };

        Callable<Map<String, Object>> decorated = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        assertThatThrownBy(decorated::call).isInstanceOf(TimeoutException.class);
        assertThat(taskStarted.get()).isTrue();
        // cancel(true) 호출로 Future가 취소됨
        assertThat(future.isCancelled()).isTrue();
    }

    /**
     * cancelRunningFuture=false이면 타임아웃 발생해도 작업이 계속 실행되는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 3s + timeoutDuration=1s + cancelRunningFuture=false
     *   → 1초 후 TimeoutException 발생 (호출자에게 반환)
     *   → 하지만 Future는 취소되지 않음 → 3초 후 정상 완료
     *
     * 핵심:
     *   cancelRunningFuture=false이면 타임아웃은 호출자에게만 알리고,
     *   실제 작업은 백그라운드에서 계속 실행된다.
     *   부수효과(DB 쓰기, 외부 API 호출 등)가 있는 작업에서
     *   중간 취소가 위험할 때 이 설정을 사용한다.
     */
    @Test
    void cancelRunningFuture_false이면_타임아웃_후에도_작업이_계속_실행된다() throws InterruptedException {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));

        TimeLimiter timeLimiter = TimeLimiter.of("tl-no-cancel-" + UUID.randomUUID(), TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(1))
                .cancelRunningFuture(false)
                .build());
        TestLogger.attach(timeLimiter);

        AtomicBoolean taskCompleted = new AtomicBoolean(false);
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        Supplier<CompletableFuture<Map<String, Object>>> futureSupplier = () -> {
            executor.submit(() -> {
                try {
                    Map<String, Object> result = paymentClient.confirm("pk_tl_nocl", "order_tl_nocl", 10000);
                    taskCompleted.set(true);
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future;
        };

        Callable<Map<String, Object>> decorated = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        // 호출자에게는 타임아웃
        assertThatThrownBy(decorated::call).isInstanceOf(TimeoutException.class);

        // Future는 취소되지 않음
        assertThat(future.isCancelled()).isFalse();

        // 작업이 완료될 때까지 대기 (최대 5초)
        Thread.sleep(4000);
        assertThat(taskCompleted.get()).isTrue();
        assertThat(future.isDone()).isTrue();
    }

    /**
     * TimeLimiter + CircuitBreaker 조합에서 타임아웃이 CB 실패로 집계되는 것을 검증한다.
     *
     * 흐름:
     *   CB(바깥) → TimeLimiter(안쪽, timeoutDuration=1s)
     *   → SLOW 5s → TimeLimiter 타임아웃 → TimeoutException
     *   → CB가 실패로 집계
     *
     * 핵심:
     *   TimeLimiter의 TimeoutException을 CB가 실패로 집계하려면
     *   recordExceptions에 TimeoutException을 포함하거나, 기본 설정(모든 예외 집계)을 사용해야 한다.
     *   readTimeout(ResourceAccessException)과 TimeLimiter(TimeoutException)는 다른 예외 타입이므로
     *   CB 설정 시 둘 다 고려해야 한다.
     */
    @Test
    void TimeLimiter_타임아웃이_CB_실패로_집계된다() {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "5000", "slowMaxMs", "5000"));

        TimeLimiter timeLimiter = TimeLimiter.of("tl-cb-" + UUID.randomUUID(), TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(1))
                .build());
        TestLogger.attach(timeLimiter);

        CircuitBreaker cb = CircuitBreaker.of("tl-cb-outer-" + UUID.randomUUID(),
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(3)
                        .slidingWindowSize(3)
                        // TimeoutException은 Exception이므로 기본 설정으로 실패 집계됨
                        .build());
        TestLogger.attach(cb);

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            // CB(바깥) → TimeLimiter(안쪽)
            Supplier<CompletableFuture<Map<String, Object>>> futureSupplier = () ->
                    CompletableFuture.supplyAsync(
                            () -> paymentClient.confirm("pk_tl_cb_" + idx, "order_tl_cb", 10000), executor);
            Callable<Map<String, Object>> tlDecorated = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

            try {
                cb.executeCallable(tlDecorated);
            } catch (Exception ignored) {}
        }

        // 3건 모두 타임아웃 → CB 실패 3건 → OPEN
        TestLogger.summary(cb);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * readTimeout vs TimeLimiter 역할 차이를 검증한다.
     *
     * 흐름:
     *   SLOW 3s + readTimeout=2s + TimeLimiter timeoutDuration=5s
     *   → readTimeout(2s)이 먼저 발동 → ResourceAccessException (TimeoutException 아님)
     *
     *   SLOW 3s + readTimeout=10s + TimeLimiter timeoutDuration=2s
     *   → TimeLimiter(2s)가 먼저 발동 → TimeoutException (ResourceAccessException 아님)
     *
     * 핵심:
     *   readTimeout: 네트워크 소켓 레벨. RestTemplate/WebClient가 서버 응답을 기다리는 최대 시간.
     *   TimeLimiter: 비즈니스 레벨. 전체 비동기 작업의 완료를 기다리는 최대 시간.
     *
     *   둘은 독립적으로 동작하며, 더 짧은 쪽이 먼저 발동한다.
     *   예외 타입이 다르므로(ResourceAccessException vs TimeoutException)
     *   CB/Retry 설정에서 둘 다 고려해야 한다.
     */
    @Test
    void readTimeout이_TimeLimiter보다_짧으면_ResourceAccessException이_먼저_발생한다() {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 2000); // readTimeout=2s

        TimeLimiter timeLimiter = TimeLimiter.of("tl-vs-read-" + UUID.randomUUID(), TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5)) // TimeLimiter=5s > readTimeout=2s
                .build());
        TestLogger.attach(timeLimiter);

        Supplier<CompletableFuture<Map<String, Object>>> futureSupplier = () ->
                CompletableFuture.supplyAsync(
                        () -> paymentClient.confirm("pk_tl_vs_read", "order_tl_vs", 10000), executor);

        Callable<Map<String, Object>> decorated = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        // readTimeout(2s)이 먼저 발동 → ResourceAccessException이 CompletableFuture를 완료시킴
        // → TimeLimiter가 ExecutionException에서 cause를 꺼내 직접 던짐
        // → 호출자에게는 ResourceAccessException이 전파됨 (TimeoutException 아님)
        assertThatThrownBy(decorated::call)
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void TimeLimiter가_readTimeout보다_짧으면_TimeoutException이_먼저_발생한다() {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "3000", "slowMaxMs", "3000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 10000); // readTimeout=10s

        TimeLimiter timeLimiter = TimeLimiter.of("tl-vs-tl-" + UUID.randomUUID(), TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(2)) // TimeLimiter=2s < readTimeout=10s
                .build());
        TestLogger.attach(timeLimiter);

        Supplier<CompletableFuture<Map<String, Object>>> futureSupplier = () ->
                CompletableFuture.supplyAsync(
                        () -> paymentClient.confirm("pk_tl_vs_tl", "order_tl_vs2", 10000), executor);

        Callable<Map<String, Object>> decorated = TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);

        // TimeLimiter(2s)가 먼저 발동 → TimeoutException
        assertThatThrownBy(decorated::call).isInstanceOf(TimeoutException.class);
    }
}
