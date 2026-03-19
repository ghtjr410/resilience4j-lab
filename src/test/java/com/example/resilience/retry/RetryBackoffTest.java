package com.example.resilience.retry;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.core.IntervalFunction;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RetryBackoffTest extends ExampleTestBase {

    /**
     * exponentialBackoff 적용 시 대기시간이 1초→2초→4초로 지수 증가하는 것을 검증한다.
     *
     * 흐름:
     *   DEAD 모드 + maxAttempts(4) + exponentialBackoff(1s, multiplier=2)
     *   → 4회 시도, 3번 대기: 1초 + 2초 + 4초 = 최소 7초 소요
     *
     * 핵심:
     *   고정 대기(waitDuration)와 달리 exponentialBackoff은 재시도마다 대기가 길어진다.
     *   서버 과부하 시 점진적으로 부하를 줄여 복구 여유를 준다.
     */
    @Test
    void exponentialBackoff_적용시_대기시간이_지수적으로_증가한다() {
        paymentClient.setChaosMode("DEAD");

        Retry retry = Retry.of("backoff-exp-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(4) // 초기 1회 + 재시도 3회
                .intervalFunction(IntervalFunction.ofExponentialBackoff(1000, 2)) // 1s * 2^n
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        // supplier 호출 시점 기록 (onRetry는 대기 전에 발생하므로 supplier 진입 시점이 정확)
        List<Long> callTimestamps = new ArrayList<>();

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callTimestamps.add(System.currentTimeMillis());
            return paymentClient.confirm("pk_backoff_exp", "order_backoff", 10000);
        });

        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);

        // 4번 호출됨 (초기 1 + 재시도 3)
        assertThat(callTimestamps).hasSize(4);

        // 총 소요 시간: 1초 + 2초 + 4초 = 최소 7초
        long totalElapsed = callTimestamps.get(3) - callTimestamps.get(0);
        assertThat(totalElapsed).isGreaterThanOrEqualTo(6500L);

        // 각 대기 간격 검증 (±500ms 허용)
        long interval1 = callTimestamps.get(1) - callTimestamps.get(0); // ~1초
        long interval2 = callTimestamps.get(2) - callTimestamps.get(1); // ~2초
        long interval3 = callTimestamps.get(3) - callTimestamps.get(2); // ~4초

        assertThat(interval1).isBetween(800L, 1500L);
        assertThat(interval2).isBetween(1800L, 2500L);
        assertThat(interval3).isBetween(3800L, 4500L);
    }

    /**
     * exponentialRandomBackoff(jitter) 적용 시 대기시간이 랜덤 범위 안에 있는 것을 검증한다.
     *
     * 흐름:
     *   DEAD 모드 + maxAttempts(4) + exponentialRandomBackoff(1s, multiplier=2, randomizationFactor=0.5)
     *   → 기본 대기: 1s, 2s, 4s
     *   → jitter 0.5 적용: [0.5s~1.5s], [1s~3s], [2s~6s]
     *
     * 핵심:
     *   jitter 없이 동일한 exponentialBackoff을 사용하면
     *   여러 클라이언트가 동시에 재시도하여 "thundering herd" 문제가 발생한다.
     *   jitter는 재시도 시점을 분산시켜 서버 부하를 고르게 만든다.
     */
    @Test
    void exponentialRandomBackoff_적용시_대기시간이_jitter_범위_안에_있다() {
        paymentClient.setChaosMode("DEAD");

        // randomizationFactor=0.5 → 기본 대기의 ±50% 범위
        Retry retry = Retry.of("backoff-jitter-" + UUID.randomUUID(), RetryConfig.custom()
                .maxAttempts(4)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(1000, 2, 0.5))
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
        TestLogger.attach(retry);

        List<Long> callTimestamps = new ArrayList<>();

        Supplier<Map<String, Object>> decorated = Retry.decorateSupplier(retry, () -> {
            callTimestamps.add(System.currentTimeMillis());
            return paymentClient.confirm("pk_backoff_jitter", "order_backoff_j", 10000);
        });

        assertThatThrownBy(decorated::get).isInstanceOf(HttpServerErrorException.class);
        assertThat(callTimestamps).hasSize(4);

        // 각 대기 간격이 jitter 범위 안에 있는지 검증
        // 기본 1s ± 50% → [500ms, 1500ms]
        long interval1 = callTimestamps.get(1) - callTimestamps.get(0);
        assertThat(interval1).isBetween(400L, 1600L);

        // 기본 2s ± 50% → [1000ms, 3000ms]
        long interval2 = callTimestamps.get(2) - callTimestamps.get(1);
        assertThat(interval2).isBetween(900L, 3100L);

        // 기본 4s ± 50% → [2000ms, 6000ms]
        long interval3 = callTimestamps.get(3) - callTimestamps.get(2);
        assertThat(interval3).isBetween(1900L, 6100L);
    }
}
