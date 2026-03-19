package com.example.resilience.timeout;

import com.example.resilience.ExampleTestBase;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TimeoutTest extends ExampleTestBase {

    /**
     * TIMEOUT 모드에서 readTimeout 초과 시 ResourceAccessException이 발생하는 것을 검증한다.
     *
     * 흐름:
     *   TIMEOUT 모드(서버가 응답하지 않음) + readTimeout=2s
     *   → 약 2초 후 ResourceAccessException 발생
     *
     * 핵심:
     *   서버가 응답하지 않을 때 클라이언트의 readTimeout이 보호 역할을 한다.
     *   ResourceAccessException은 네트워크 레벨 에러로, Retry/CB에서 장애로 처리해야 한다.
     */
    @Test
    void TIMEOUT에서_readTimeout_초과시_ResourceAccessException_발생() {
        paymentClient.setChaosMode("TIMEOUT");
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 2000); // readTimeout=2s

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> paymentClient.confirm("pk_timeout", "order_timeout", 10000))
                .isInstanceOf(ResourceAccessException.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isBetween(1500L, 3000L); // ~2s
    }

    /**
     * SLOW 응답이 readTimeout 이내이면 정상 성공하는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 2s + readTimeout=5s → 응답 시간(2s) < readTimeout(5s) → 정상 성공
     *   → elapsed >= 2s
     *
     * 핵심:
     *   느린 응답도 readTimeout 이내라면 정상적으로 처리된다.
     *   단, slowCallDurationThreshold로 slow 감지를 설정하면 CB에서 장애 전조로 집계할 수 있다.
     */
    @Test
    void SLOW_응답이_readTimeout_이내이면_성공한다() {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 5000); // readTimeout=5s

        long start = System.currentTimeMillis();
        Map<String, Object> result = paymentClient.confirm("pk_slow_ok", "order_slow_ok", 10000);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isNotNull();
        assertThat(elapsed).isGreaterThanOrEqualTo(2000L);
    }

    /**
     * SLOW 응답이 readTimeout을 초과하면 ResourceAccessException이 발생하는 것을 검증한다.
     *
     * 흐름:
     *   SLOW 2s + readTimeout=1s → 응답 시간(2s) > readTimeout(1s)
     *   → ResourceAccessException 발생
     *
     * 핵심:
     *   readTimeout을 너무 짧게 설정하면 정상적이지만 느린 응답도 실패로 처리된다.
     *   readTimeout은 slowCallDurationThreshold보다 크게 설정하는 것이 일반적이다.
     */
    @Test
    void SLOW_응답이_readTimeout을_초과하면_ResourceAccessException_발생() {
        paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2000"));
        paymentClient.configure(
                "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                5000, 1000); // readTimeout=1s

        assertThatThrownBy(() -> paymentClient.confirm("pk_slow_fail", "order_slow_fail", 10000))
                .isInstanceOf(ResourceAccessException.class);
    }
}
