package com.example.resilience.circuitbreaker;

import com.example.resilience.ExampleTestBase;
import com.example.resilience.TestLogger;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CircuitBreakerTrapTest extends ExampleTestBase {

    @Nested
    class 함정1_minimumNumberOfCalls {

        /**
         * minimumNumberOfCalls 기본값(100)이면 10건 실패해도 서킷이 안 열리는 문제를 검증한다.
         *
         * 흐름:
         *   DEAD 모드 → 10건 요청 전부 실패
         *   → minimumNumberOfCalls(100) 미달 → 실패율 평가 자체가 안 됨 → CLOSED 유지
         *
         * 문제:
         *   기본값이 100으로 너무 크기 때문에, 소규모 트래픽 환경에서는
         *   서버가 완전히 죽어도 서킷이 열리지 않는다.
         */
        @Test
        void 기본값_100이면_10건_실패해도_서킷이_안_열린다() {
            paymentClient.setChaosMode("DEAD");

            CircuitBreaker cb = CircuitBreaker.of("trap1-before-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .slidingWindowSize(100)
                            // minimumNumberOfCalls 기본값 = 100
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 10; i++) {
                String key = "pk_trap1b_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap1", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            // 10건밖에 안 했으므로 minimumNumberOfCalls(100) 미달 → 평가 안 됨
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        /**
         * minimumNumberOfCalls를 5로 낮추면 5건 실패로 서킷이 열리는 것을 검증한다.
         *
         * 해결:
         *   minimumNumberOfCalls를 서비스 트래픽에 맞게 낮추면
         *   소규모 트래픽에서도 빠르게 장애를 감지할 수 있다.
         */
        @Test
        void minimumNumberOfCalls를_5로_설정하면_5건_실패로_OPEN_전환() {
            paymentClient.setChaosMode("DEAD");

            CircuitBreaker cb = CircuitBreaker.of("trap1-after-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(5)
                            .slidingWindowSize(10)
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 5; i++) {
                String key = "pk_trap1a_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap1", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    class 함정2_automaticTransitionFromOpenToHalfOpenEnabled {

        /**
         * 자동 전환 미설정(기본값 false)이면 waitDuration이 지나도 OPEN에 머무는 문제를 검증한다.
         *
         * 흐름:
         *   DEAD → 5건 실패 → OPEN 전환
         *   → waitDuration(1s) 대기 → 여전히 OPEN (자동 전환 없음)
         *
         * 문제:
         *   기본값이 false이므로, 다음 요청이 올 때까지 HALF_OPEN으로 전환되지 않는다.
         *   트래픽이 적은 환경에서는 서킷이 OPEN에 오래 갇힐 수 있다.
         */
        @Test
        void 기본값_false이면_waitDuration_경과_후에도_여전히_OPEN() throws InterruptedException {
            paymentClient.setChaosMode("DEAD");

            CircuitBreaker cb = CircuitBreaker.of("trap2-before-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(5)
                            .slidingWindowSize(5)
                            .waitDurationInOpenState(Duration.ofSeconds(1))
                            // automaticTransitionFromOpenToHalfOpenEnabled 기본값 = false
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            // OPEN 진입
            for (int i = 0; i < 5; i++) {
                String key = "pk_trap2b_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap2", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // waitDuration만큼 대기해도 자동 전환 없음
            Thread.sleep(1500);
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        /**
         * 자동 전환을 true로 설정하면 waitDuration 후 자동으로 HALF_OPEN이 되는 것을 검증한다.
         *
         * 해결:
         *   automaticTransitionFromOpenToHalfOpenEnabled(true) 설정 시
         *   요청이 없어도 타이머에 의해 자동으로 HALF_OPEN으로 전환된다.
         */
        @Test
        void true로_설정하면_sleep_후_자동_HALF_OPEN_전환() throws InterruptedException {
            paymentClient.setChaosMode("DEAD");

            CircuitBreaker cb = CircuitBreaker.of("trap2-after-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(5)
                            .slidingWindowSize(5)
                            .waitDurationInOpenState(Duration.ofSeconds(1))
                            .automaticTransitionFromOpenToHalfOpenEnabled(true)
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 5; i++) {
                String key = "pk_trap2a_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap2", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            Thread.sleep(1500);
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        }
    }

    @Nested
    class 함정3_ignoreExceptions {

        /**
         * ignoreExceptions 미설정 시 비즈니스 에러(403)가 실패로 집계되어 서킷이 오진하는 문제를 검증한다.
         *
         * 흐름:
         *   NORMAL 모드 → reject_company 트리거로 403 응답 5건
         *   → HttpClientErrorException이 recordExceptions에 포함 → 실패율 100% → OPEN
         *
         * 문제:
         *   비즈니스 에러(4xx)는 서버 장애가 아닌데 서킷을 여는 오진이 발생한다.
         */
        @Test
        void 미설정시_비즈니스_에러_403이_실패로_카운트되어_OPEN_오진() {
            // reject_company 트리거: orderId에 "reject_company" 포함 시 403
            paymentClient.setChaosMode("NORMAL");

            CircuitBreaker cb = CircuitBreaker.of("trap3-before-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(5)
                            .slidingWindowSize(5)
                            .recordExceptions(HttpServerErrorException.class, HttpClientErrorException.class, ResourceAccessException.class)
                            // ignoreExceptions 미설정 → 4xx도 실패로 카운트
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 5; i++) {
                String key = "pk_trap3b_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "reject_company", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            // 비즈니스 에러(403)가 실패로 집계 → OPEN (오진)
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        /**
         * ignoreExceptions에 HttpClientErrorException을 추가하면 403이 무시되어 CLOSED를 유지하는 것을 검증한다.
         *
         * 해결:
         *   ignoreExceptions(HttpClientErrorException.class) 설정으로
         *   비즈니스 에러를 서킷 집계에서 제외하면 오진을 방지할 수 있다.
         */
        @Test
        void ignoreExceptions_설정시_403_무시하고_CLOSED_유지() {
            paymentClient.setChaosMode("NORMAL");

            CircuitBreaker cb = CircuitBreaker.of("trap3-after-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(5)
                            .slidingWindowSize(5)
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .ignoreExceptions(HttpClientErrorException.class)
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 5; i++) {
                String key = "pk_trap3a_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "reject_company", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            // 403은 무시됨 → CLOSED
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    @Nested
    class 함정4_slidingWindowSize_기본값_100 {

        /**
         * slidingWindowSize 기본값(100)이지만 minimumNumberOfCalls를 10으로 설정한 경우,
         * 10건 전부 실패하면 실패율 100%로 OPEN이 되는 것을 검증한다.
         *
         * 흐름:
         *   DEAD → 10건 실패 → minimumNumberOfCalls(10) 충족 → 실패율 100% → OPEN
         *
         * 핵심:
         *   slidingWindowSize가 크더라도 minimumNumberOfCalls만 충족하면 평가는 수행된다.
         *   진짜 함정은 minimumNumberOfCalls 기본값도 100인 경우이다.
         */
        @Test
        void slidingWindowSize_100이지만_minimumNumberOfCalls_10_충족시_OPEN() {
            paymentClient.setChaosMode("DEAD");

            CircuitBreaker cb = CircuitBreaker.of("trap6-before-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(10)
                            // slidingWindowSize 기본값 = 100
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            // 10건 실패 (minimumNumberOfCalls=10 충족) 하지만 window=100 중 10건만 채워짐
            // minimumNumberOfCalls(10)은 충족되므로 실패율 계산은 됨
            // 하지만 기본 slidingWindowSize=100이므로 100건 중 10건만 있는 상태
            for (int i = 0; i < 10; i++) {
                String key = "pk_trap6b_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap6", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            // 10건 중 10건 실패 → 실패율 100% → 사실 OPEN이 됨
            // 진짜 함정: minimumNumberOfCalls 기본값도 100이면 여기서 CLOSED
            // slidingWindowSize=100 + minimumNumberOfCalls=100 → 100건 채워야 평가
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        /**
         * slidingWindowSize와 minimumNumberOfCalls 모두 기본값(100)이면
         * 10건 실패해도 서킷이 열리지 않는 진짜 함정을 검증한다.
         *
         * 흐름:
         *   DEAD → 10건 실패 → minimumNumberOfCalls(100) 미달 → 평가 안 됨 → CLOSED
         *
         * 문제:
         *   두 값 모두 기본값 100을 사용하면, 100건이 쌓이기 전까지
         *   서버가 완전히 죽어도 서킷이 열리지 않는다.
         */
        @Test
        void 둘_다_기본값_100이면_10건_실패해도_평가_안_되어_CLOSED() {
            paymentClient.setChaosMode("DEAD");

            CircuitBreaker cb = CircuitBreaker.of("trap6-before2-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            // slidingWindowSize 기본값 = 100, minimumNumberOfCalls 기본값 = 100
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 10; i++) {
                String key = "pk_trap6b2_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap6", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            // minimumNumberOfCalls(100) 미달 → 평가 안 됨 → CLOSED
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        /**
         * slidingWindowSize=10, minimumNumberOfCalls=5로 설정하면 빠르게 장애를 감지하는 것을 검증한다.
         *
         * 해결:
         *   두 값을 서비스 트래픽에 맞게 낮추면 5건만 실패해도 서킷이 열린다.
         */
        @Test
        void slidingWindowSize_10_minimumNumberOfCalls_5로_설정하면_빠른_감지() {
            paymentClient.setChaosMode("DEAD");

            CircuitBreaker cb = CircuitBreaker.of("trap6-after-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .slidingWindowSize(10)
                            .minimumNumberOfCalls(5)
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 5; i++) {
                String key = "pk_trap6a_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap6", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    class 함정5_slidingWindowSize가_minimumNumberOfCalls보다_작을_때 {

        /**
         * slidingWindowSize < minimumNumberOfCalls이면 Resilience4j가 minimumNumberOfCalls를
         * slidingWindowSize로 자동 보정하여, 의도한 것보다 빨리 서킷이 열리는 함정을 검증한다.
         *
         * 흐름:
         *   slidingWindowSize=5, minimumNumberOfCalls=10
         *   → Resilience4j 내부에서 minimumNumberOfCalls를 5로 자동 보정
         *   → 5건 실패만으로 OPEN (의도한 10건이 아님!)
         *
         * 핵심:
         *   slidingWindowSize < minimumNumberOfCalls로 설정하면 Resilience4j가
         *   조용히 minimumNumberOfCalls를 slidingWindowSize로 맞춘다.
         *   에러나 경고 없이 자동 보정되므로, 개발자는 "10건 이상 실패해야 열린다"고
         *   기대하지만 실제로는 5건만에 열린다.
         *
         *   설정 리뷰 시 slidingWindowSize >= minimumNumberOfCalls인지 반드시 확인해야 한다.
         */
        @Test
        void slidingWindowSize가_minimumNumberOfCalls보다_작으면_자동_보정되어_의도보다_빨리_열린다() {
            paymentClient.setChaosMode("DEAD");

            CircuitBreaker cb = CircuitBreaker.of("trap-window-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .slidingWindowSize(5)          // 윈도우 최대 5건
                            .minimumNumberOfCalls(10)       // 의도: 10건 후 평가
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            // 5건만 실패해도 OPEN (minimumNumberOfCalls가 5로 자동 보정됨)
            for (int i = 0; i < 5; i++) {
                String key = "pk_trap_win_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap_win", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            // 의도한 10건이 아니라 5건만에 OPEN! (자동 보정)
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        /**
         * slidingWindowSize >= minimumNumberOfCalls로 설정하면 의도한 대로 동작하는 것을 검증한다.
         *
         * 해결:
         *   slidingWindowSize를 minimumNumberOfCalls 이상으로 설정해야 한다.
         *   이렇게 하면 자동 보정 없이 정확히 의도한 건수 후에 평가가 시작된다.
         */
        @Test
        void slidingWindowSize가_minimumNumberOfCalls_이상이면_정상_동작한다() {
            paymentClient.setChaosMode("DEAD");

            CircuitBreaker cb = CircuitBreaker.of("trap-window-fix-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .slidingWindowSize(10)         // 윈도우 10건
                            .minimumNumberOfCalls(10)       // 평가 시작 조건 10건 (동일)
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 10; i++) {
                String key = "pk_trap_win_fix_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap_win_fix", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            // 10건 실패 → minimumNumberOfCalls 충족 → 실패율 100% → OPEN
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    class 함정6_slowCallDurationThreshold {


        /**
         * slowCallDurationThreshold(5s)가 readTimeout(3s)보다 크면 slow 호출을 감지하지 못하는 문제를 검증한다.
         *
         * 흐름:
         *   SLOW 2~2.5s 설정 + readTimeout=3s + threshold=5s
         *   → 응답 시간(2~2.5s) < threshold(5s) → slow로 안 잡힘 → CLOSED
         *
         * 문제:
         *   threshold가 readTimeout보다 크면 타임아웃이 먼저 발생하므로
         *   slow 감지가 의미 없어진다.
         */
        @Test
        void threshold가_readTimeout보다_크면_slow_호출이_감지되지_않는다() {
            // SLOW 모드: 2~2.5s 지연
            paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2500"));
            paymentClient.configure(
                    "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                    5000, 3000); // readTimeout=3s

            CircuitBreaker cb = CircuitBreaker.of("trap4-before-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(50)
                            .slowCallDurationThreshold(Duration.ofSeconds(5)) // threshold(5s) > readTimeout(3s)
                            .slowCallRateThreshold(50)
                            .minimumNumberOfCalls(5)
                            .slidingWindowSize(5)
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 5; i++) {
                String key = "pk_trap4b_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap4", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            // 2~2.5s < 5s(threshold) → slow로 안 잡힘 → CLOSED
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        /**
         * threshold(1s) < readTimeout(5s)로 설정하면 SLOW(2~2.5s) 호출이 감지되어 OPEN이 되는 것을 검증한다.
         *
         * 해결:
         *   slowCallDurationThreshold는 반드시 readTimeout보다 작게 설정해야
         *   타임아웃 전에 slow 호출을 감지할 수 있다.
         */
        @Test
        void threshold를_readTimeout보다_작게_설정하면_slow_감지_후_OPEN() {
            paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "2000", "slowMaxMs", "2500"));
            paymentClient.configure(
                    "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                    5000, 5000); // readTimeout=5s

            CircuitBreaker cb = CircuitBreaker.of("trap4-after-" + UUID.randomUUID(),
                    CircuitBreakerConfig.custom()
                            .failureRateThreshold(100) // 일반 실패로는 안 열리게
                            .slowCallDurationThreshold(Duration.ofSeconds(1)) // threshold(1s) < SLOW(2~2.5s)
                            .slowCallRateThreshold(50)
                            .minimumNumberOfCalls(5)
                            .slidingWindowSize(5)
                            .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                            .build());
            TestLogger.attach(cb);

            for (int i = 0; i < 5; i++) {
                String key = "pk_trap4a_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap4", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }

            // 2~2.5s > 1s(threshold) → slow 감지 → OPEN
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    class 함정7_maxWaitDurationInHalfOpenState_기본값_0 {

        private CircuitBreaker openCircuit(CircuitBreakerConfig config) {
            CircuitBreaker cb = CircuitBreaker.of("trap7-" + UUID.randomUUID(), config);
            TestLogger.attach(cb);
            paymentClient.setChaosMode("DEAD");
            for (int i = 0; i < 5; i++) {
                String key = "pk_trap7_" + i;
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm(key, "order_trap7", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            }
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            return cb;
        }

        /**
         * maxWaitDurationInHalfOpenState 기본값(0=무한)이면 HALF_OPEN에서 SLOW 요청 대기 중 갇히는 문제를 검증한다.
         *
         * 흐름:
         *   OPEN → 수동 HALF_OPEN 전환 → SLOW(5s) 요청 1건 시작
         *   → 3초 후에도 여전히 HALF_OPEN (무한 대기)
         *
         * 문제:
         *   기본값 0(무한 대기)이므로 permitted 호출이 느리면
         *   HALF_OPEN에서 영원히 빠져나오지 못한다.
         */
        @Test
        void 기본값_0이면_HALF_OPEN에서_SLOW_요청_대기중_갇힌다() throws Exception {
            CircuitBreaker cb = openCircuit(CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .minimumNumberOfCalls(5)
                    .slidingWindowSize(5)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .permittedNumberOfCallsInHalfOpenState(3)
                    .automaticTransitionFromOpenToHalfOpenEnabled(true)
                    // maxWaitDurationInHalfOpenState 기본값 = 0 (무한)
                    .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                    .build());

            paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "5000", "slowMaxMs", "5000"));
            paymentClient.configure(
                    "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                    5000, 10000);

            // 수동 HALF_OPEN 전환
            cb.transitionToHalfOpenState();
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            // 1건 SLOW 호출 시작
            var executor = java.util.concurrent.Executors.newFixedThreadPool(1);
            executor.submit(() -> {
                Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                        () -> paymentClient.confirm("pk_trap7_slow", "order_trap7_slow", 10000));
                try { decorated.get(); } catch (Exception ignored) {}
            });

            // 3초 후에도 HALF_OPEN에 갇혀 있음 (무한 대기)
            Thread.sleep(3000);
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            executor.shutdownNow();
        }

        /**
         * maxWaitDurationInHalfOpenState=2s로 설정하면 시간 초과 시 OPEN으로 복귀하는 것을 검증한다.
         *
         * 해결:
         *   maxWaitDurationInHalfOpenState를 설정하면 permitted 호출이 느려도
         *   지정 시간 후 강제로 OPEN으로 복귀하여 무한 대기를 방지한다.
         */
        @Test
        void maxWaitDuration_2s_설정시_시간_초과하면_OPEN_복귀() throws Exception {
            // waitDurationInOpenState를 길게 → maxWait→OPEN 후 재전환 방지
            CircuitBreaker cb = openCircuit(CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .minimumNumberOfCalls(5)
                    .slidingWindowSize(5)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .permittedNumberOfCallsInHalfOpenState(3)
                    .maxWaitDurationInHalfOpenState(Duration.ofSeconds(2))
                    .automaticTransitionFromOpenToHalfOpenEnabled(true)
                    .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                    .build());

            paymentClient.setChaosMode("SLOW", Map.of("slowMinMs", "10000", "slowMaxMs", "10000"));
            paymentClient.configure(
                    "http://" + mockToss.getHost() + ":" + mockToss.getMappedPort(8090),
                    5000, 15000);

            // 수동으로 HALF_OPEN 전환 (30s 대기 불필요)
            cb.transitionToHalfOpenState();
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                executor.submit(() -> {
                    Supplier<Map<String, Object>> decorated = CircuitBreaker.decorateSupplier(cb,
                            () -> paymentClient.confirm("pk_trap7_mw_" + idx, "order_trap7_mw", 10000));
                    try { decorated.get(); } catch (Exception ignored) {}
                });
            }

            // maxWaitDuration(2s) 후 강제 OPEN 복귀
            Thread.sleep(3000);
            TestLogger.summary(cb);
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            executor.shutdownNow();
        }
    }
}
