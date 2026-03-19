package com.example.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;

import java.util.concurrent.atomic.AtomicInteger;

public class TestLogger {

    public static void attach(CircuitBreaker cb) {
        var successCount = new AtomicInteger(0);
        var failCount = new AtomicInteger(0);
        var notPermittedCount = new AtomicInteger(0);

        cb.getEventPublisher()
            .onStateTransition(e -> System.out.printf(
                "  ⚡ [CB 상태 전이] %s → %s%n",
                e.getStateTransition().getFromState(),
                e.getStateTransition().getToState()))
            .onSuccess(e -> System.out.printf(
                "  ✅ [CB 성공] elapsed=%dms | 성공=%d 실패=%d 차단=%d%n",
                e.getElapsedDuration().toMillis(),
                successCount.incrementAndGet(), failCount.get(), notPermittedCount.get()))
            .onError(e -> System.out.printf(
                "  ❌ [CB 실패] %s | elapsed=%dms | 성공=%d 실패=%d%n",
                e.getThrowable().getClass().getSimpleName(),
                e.getElapsedDuration().toMillis(),
                successCount.get(), failCount.incrementAndGet()))
            .onCallNotPermitted(e -> {
                notPermittedCount.incrementAndGet();
                System.out.printf("  🚫 [CB 차단] 서킷 %s — 요청 거부됨%n", cb.getState());
            })
            .onSlowCallRateExceeded(e -> System.out.printf(
                "  🐢 [CB SLOW 초과] slowCallRate=%.1f%%%n",
                cb.getMetrics().getSlowCallRate()))
            .onFailureRateExceeded(e -> System.out.printf(
                "  📈 [CB 실패율 초과] failureRate=%.1f%%%n",
                cb.getMetrics().getFailureRate()));
    }

    public static void attach(Retry retry) {
        retry.getEventPublisher()
            .onRetry(e -> System.out.printf(
                "  🔄 [RETRY #%d] %s%n",
                e.getNumberOfRetryAttempts(),
                e.getLastThrowable() != null
                        ? e.getLastThrowable().getClass().getSimpleName()
                        : "result mismatch"))
            .onSuccess(e -> System.out.printf(
                "  ✅ [RETRY 성공] attempts=%d%n",
                e.getNumberOfRetryAttempts()))
            .onError(e -> System.out.printf(
                "  ❌ [RETRY 최종 실패] attempts=%d | %s%n",
                e.getNumberOfRetryAttempts(),
                e.getLastThrowable().getClass().getSimpleName()));
    }

    public static void attach(Bulkhead bulkhead) {
        bulkhead.getEventPublisher()
            .onCallPermitted(e -> {})
            .onCallRejected(e -> System.out.printf(
                "  🚫 [BULKHEAD 거절] 동시호출 한도 초과%n"))
            .onCallFinished(e -> {});
    }

    public static void attach(RateLimiter rateLimiter) {
        rateLimiter.getEventPublisher()
            .onSuccess(e -> System.out.printf(
                "  ✅ [RL 허용] %s%n", rateLimiter.getName()))
            .onFailure(e -> System.out.printf(
                "  🚫 [RL 거절] %s — 허용량 초과%n", rateLimiter.getName()));
    }

    public static void attach(TimeLimiter timeLimiter) {
        timeLimiter.getEventPublisher()
            .onSuccess(e -> System.out.printf(
                "  ✅ [TL 성공] %s%n", timeLimiter.getName()))
            .onTimeout(e -> System.out.printf(
                "  ⏱️ [TL 타임아웃] %s%n", timeLimiter.getName()))
            .onError(e -> System.out.printf(
                "  ❌ [TL 에러] %s | %s%n",
                timeLimiter.getName(),
                e.getThrowable().getClass().getSimpleName()));
    }

    public static void summary(CircuitBreaker cb) {
        var m = cb.getMetrics();
        System.out.printf("""
            ── CB 요약 ──────────────────────────
            상태: %s | 실패율: %.1f%% | slowCall율: %.1f%%
            성공: %d | 실패: %d | 차단: %d | slow: %d
            ─────────────────────────────────────
            """,
            cb.getState(), m.getFailureRate(), m.getSlowCallRate(),
            m.getNumberOfSuccessfulCalls(), m.getNumberOfFailedCalls(),
            m.getNumberOfNotPermittedCalls(), m.getNumberOfSlowCalls());
    }
}
