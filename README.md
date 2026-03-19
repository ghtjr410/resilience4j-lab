# Resilience4j 학습 테스트

Mock 서버를 대상으로 Resilience4j의 핵심 동작을 증명하는 테스트 모음.

## 실행

```bash
./gradlew test
```

> mock-toss 컨테이너를 Docker Hub(`ghtjr410/mock-toss`)에서 자동으로 pull합니다.
> Docker가 실행 중이어야 합니다.

## TestLogger

테스트 실행 중 Resilience4j 이벤트를 실시간 로깅하는 유틸리티.
자체 `AtomicInteger` 카운터로 누적값을 관리한다 — Resilience4j 내부의 이벤트/메트릭 타이밍 불일치 문제를 우회하기 위함.
(상세: [`circuitbreaker/README.md`](src/test/java/com/example/resilience/circuitbreaker/README.md#testlogger-이벤트-타이밍-문제))

---

## circuitbreaker/

서킷브레이커의 상태 전이, slowCall 감지, HALF-OPEN 동작, 예외 처리, 설정 함정.

### CircuitBreakerBasicTest

| 테스트 | 증명 |
|--------|------|
| DEAD 모드에서 모든 요청 실패시 서킷이 OPEN으로 전환된다 | 실패율 100% → CLOSED→OPEN 전이, 이후 CallNotPermittedException |
| PARTIAL FAILURE 30퍼센트이면 실패율 50퍼센트 미만으로 CLOSED 유지 | slidingWindowSize=100으로 확률적 편차 억제 |
| PARTIAL FAILURE 90퍼센트이면 실패율 50퍼센트 초과로 OPEN 전환 | 실패율 > threshold → OPEN 전환 |
| recordResult로 예외 없는 응답도 실패로 집계할 수 있다 | 200 OK + IN_PROGRESS → 결과값 기반 실패 판정 |
| recordResult 조건 불일치시 성공으로 집계된다 | DONE 응답 → predicate 불일치 → 성공 |

### CircuitBreakerRecoveryTest

| 테스트 | 증명 |
|--------|------|
| HALF OPEN에서 성공하면 CLOSED로 복구된다 | OPEN→HALF_OPEN→CLOSED 복구 흐름 |
| HALF OPEN에서 실패하면 OPEN으로 재진입한다 | HALF_OPEN→OPEN 재차단 |
| CLOSED 복구 후 슬라이딩 윈도우가 초기화되어 이전 실패가 이월되지 않는다 | 복구 후 40% < 50% → CLOSED 유지 |
| HALF OPEN에서 실패율이 threshold 미만이면 CLOSED로 복구된다 | 33% < 50% → CLOSED |
| HALF OPEN에서 실패율이 threshold 이상이면 OPEN으로 재진입한다 | 66% > 50% → OPEN |

### CircuitBreakerResetTest

| 테스트 | 증명 |
|--------|------|
| OPEN에서 reset 호출시 CLOSED로 복구되고 메트릭이 초기화된다 | 수동 리셋 — 운영 중 긴급 복구 |
| FORCED OPEN에서 reset 호출시 CLOSED로 복구되고 정상 요청이 통과한다 | PG 점검 후 즉시 복구 |
| reset 후 이전 실패 이력이 이월되지 않는다 | sliding window까지 완전 초기화 |

### SlowCallDetectionTest

| 테스트 | 증명 |
|--------|------|
| SLOW 전부이면 slowCallRate 100퍼센트로 OPEN | 느린 응답만으로 서킷 열림 |
| slowCall은 요청을 끊지 않고 느린 성공도 장애 전조로 집계한다 | timeout과 달리 응답은 정상 반환, 집계만 됨 |
| slowCall과 failure 복합시 둘 중 하나라도 threshold 넘으면 OPEN | failureRate와 slowCallRate 독립 판정 |
| HALF OPEN에서 느린 성공만으로도 slowCallRate 초과시 OPEN 재진입한다 | 성공해도 느리면 복구 안 됨 |

### HalfOpenBehaviorTest

| 테스트 | 증명 |
|--------|------|
| HALF OPEN에서 permittedNumberOfCalls 초과 요청은 거절된다 | 허용 슬롯 초과 시 CallNotPermittedException |
| maxWaitDurationInHalfOpenState 기본값 0이면 무한 대기 | 느린 요청이 슬롯 점유 시 HALF_OPEN에 갇힘 |
| maxWaitDurationInHalfOpenState 설정시 시간 초과하면 OPEN 복귀 | 타임아웃으로 OPEN 강제 복귀 |

### ExceptionHandlingTest

| 테스트 | 증명 |
|--------|------|
| 기본 동작에서 모든 예외가 실패로 집계되어 비즈니스 에러도 서킷 오진 | 403도 실패 집계 → 서킷 오진 |
| ignoreExceptions 설정시 특정 예외가 집계에서 무시된다 | 비즈니스 예외를 집계에서 제외 |
| recordExceptions에 지정한 예외만 실패로 기록된다 | 화이트리스트 방식 실패 기록 |
| recordFailurePredicate로 커스텀 실패 판단 5xx만 실패 | 조건부 실패 판단 |
| ignoreExceptions가 recordExceptions보다 우선한다 | 양쪽에 있으면 ignore 우선 |

### ExceptionInheritanceTest

| 테스트 | 증명 |
|--------|------|
| recordExceptions에 부모 예외 지정시 자식 예외도 실패로 집계된다 | instanceof 기반 판정 — 예외 계층 상속 |
| 부모 예외 지정시 4xx 자식도 실패로 집계되어 오진이 발생한다 | HttpStatusCodeException → 4xx/5xx 구분 불가 |
| 부모 예외 record와 ignoreExceptions로 자식을 선별적으로 제외한다 | 넓게 record + 좁게 ignore 패턴 |

### IgnoreInHalfOpenTest

| 테스트 | 증명 |
|--------|------|
| HALF OPEN에서 모든 permitted가 ignore되면 판정 불가로 HALF OPEN에 머문다 | permitted 소모하되 결과 불포함 → 상태 전이 불가 |
| HALF OPEN에서 ignore와 성공이 섞이면 성공만으로 판정된다 | ignore는 "없었던 것" — 성공만으로 CLOSED |
| HALF OPEN에서 ignore와 실패가 섞이면 실패만으로 판정된다 | ignore 제외 후 실패율 계산 → OPEN 재진입 |

### WaitIntervalFunctionTest

| 테스트 | 증명 |
|--------|------|
| exponential backoff로 OPEN 대기 시간이 실패 반복마다 증가한다 | 1차 1초 → 2차 2초 (지수 증가) |
| 고정 waitDuration은 실패 반복해도 대기 시간이 동일하다 | 대비: 고정 vs exponential backoff |

### CircuitBreakerTrapTest

| 테스트 (Before/After 쌍) | 함정 |
|--------------------------|------|
| 함정1: `minimumNumberOfCalls` | 기본값 100 → 실패해도 서킷 안 열림 |
| 함정2: `automaticTransitionFromOpenToHalfOpenEnabled` | 기본값 false → 호출 없으면 OPEN에 영원히 머무름 |
| 함정3: `ignoreExceptions` | 미설정 → 비즈니스 에러가 실패로 오진 |
| 함정4: `slidingWindowSize` | 기본값 100 → 장애 감지 지연 |
| 함정5: `slidingWindowSize < minimumNumberOfCalls` | 자동 보정되어 의도보다 빨리 서킷 열림 |
| 함정6: `slowCallDurationThreshold` | threshold > readTimeout → slowCall 감지 불가 |
| 함정7: `maxWaitDurationInHalfOpenState` | 기본값 0 → HALF_OPEN 무한 대기 |

### TimeBasedWindowTest

| 테스트 | 증명 |
|--------|------|
| TIME BASED 윈도우에서 시간 경과 후 이전 실패가 만료된다 | COUNT_BASED와의 핵심 차이: 시간 경과 시 오래된 실패 자동 폐기 |

### SpecialStateTest

| 테스트 | 증명 |
|--------|------|
| FORCED OPEN 상태에서는 모든 요청이 거부된다 | 수동 서킷 차단 — PG 점검 시나리오 |
| DISABLED 상태에서는 서킷이 항상 허용하고 상태 전이가 없다 | 서킷 비활성화 — 차단/메트릭 없음 |
| METRICS ONLY 상태에서는 메트릭만 수집하고 차단하지 않는다 | 프로덕션 검증용 — 차단 없이 메트릭만 |

---

## retry/

재시도 동작, 예외 필터, 결과 기반 재시도, ignoreExceptions, Predicate, intervalBiFunction, CircuitBreaker 조합.

### RetryBasicTest

| 테스트 | 증명 |
|--------|------|
| 첫 시도 실패 후 재시도에서 성공한다 | DEAD→NORMAL 전환으로 재시도 성공 |
| DEAD에서 3회 전부 실패하면 최종 예외가 발생한다 | maxAttempts 소진 시 예외 전파 |
| TIMEOUT에서 ResourceAccessException으로 재시도한다 | 네트워크 타임아웃도 재시도 대상 |
| retryExceptions에 없는 예외는 재시도 없이 즉시 실패한다 | 화이트리스트에 없으면 즉시 실패 |
| 비즈니스 에러 403은 재시도하지 않는다 | 비즈니스 에러 재시도 방지 |
| waitDuration 설정시 재시도 간 대기 시간이 적용된다 | 재시도 간 실제 대기 시간 검증 |
| maxAttempts는 초기 호출을 포함한 총 시도 횟수이다 | maxAttempts(3) = 초기 1 + 재시도 2 = 총 3회 |
| failAfterMaxAttempts는 예외 기반 재시도에는 적용되지 않는다 | true로 설정해도 원래 예외 전파 |
| failAfterMaxAttempts true 결과 기반에서 MaxRetriesExceededException이 발생한다 | 결과 기반에서만 래핑 예외 발생 |

### RetryExceptionFilterTest

| 테스트 | 증명 |
|--------|------|
| retryExceptions에 포함된 예외만 재시도 대상이다 | 화이트리스트 방식 재시도 |
| 비즈니스 에러 403은 재시도하지 않는다 | HttpClientErrorException 재시도 제외 |

### RetryIgnoreExceptionsTest

| 테스트 | 증명 |
|--------|------|
| ignoreExceptions에 지정된 예외는 재시도 없이 즉시 전파된다 | 명시적 재시도 금지 (블랙리스트) |
| ignoreExceptions와 retryExceptions에 동일 예외 지정시 ignore가 우선한다 | CB와 동일 원리: ignore > retry |
| ignoreExceptions에 부모 예외 지정시 자식도 재시도가 무시된다 | 예외 계층 상속 — instanceof 기반 |

### RetryExceptionPredicateTest

| 테스트 | 증명 |
|--------|------|
| retryOnException Predicate로 커스텀 재시도 조건을 적용한다 | retryExceptions 리스트와 다른 방식 |
| Predicate false이면 재시도 없이 즉시 실패한다 | 조건 불일치 → 즉시 전파 |
| Predicate로 HTTP 상태 코드별 세밀한 재시도 제어가 가능하다 | 5xx만 재시도, 4xx는 즉시 전파 |

### RetryBackoffTest

| 테스트 | 증명 |
|--------|------|
| exponentialBackoff 적용시 대기시간이 지수적으로 증가한다 | 1초→2초→4초 대기 간격 검증 |
| exponentialRandomBackoff 적용시 대기시간이 jitter 범위 안에 있다 | thundering herd 방지 — 랜덤 범위 검증 |

### RetryIntervalBiFunctionTest

| 테스트 | 증명 |
|--------|------|
| intervalBiFunction으로 예외 종류별 대기시간을 동적으로 변경한다 | timeout → 3초, 500 → 1초 |
| intervalBiFunction으로 500에러시 짧은 대기시간을 적용한다 | 일시적 오류는 짧게 재시도 |
| intervalBiFunction은 결과 기반 재시도에서도 동작한다 | Either.right → 폴링 간격 설정 |

### RetryResultPredicateTest

| 테스트 | 증명 |
|--------|------|
| 응답 status가 IN PROGRESS이면 예외 없이도 재시도한다 | HTTP 200 + 비즈니스 미완료 → 결과 기반 재시도 |
| 모든 시도에서 predicate 매칭시 마지막 결과가 반환된다 | 예외가 아닌 마지막 결과 반환 (retryExceptions와의 차이) |
| retryOnResult와 retryExceptions를 함께 사용하면 둘 다 재시도 트리거된다 | 예외 + 결과값 복합 재시도 |

### RetryWithCircuitBreakerTest

| 테스트 | 증명 |
|--------|------|
| Retry가 바깥이면 1건 요청이 서킷에 3건으로 집계된다 | 잘못된 순서: 재시도마다 CB 실패 누적 |
| CB가 바깥이면 1건 요청이 서킷에 1건으로 집계된다 | 올바른 순서: CB는 최종 결과만 집계 |
| CB가 바깥이면 Retry 내부 실패 후 최종 성공은 CB에 성공 1건으로 집계된다 | Retry 내부 실패가 CB를 오염시키지 않음 |

---

## bulkhead/

동시 호출 제한과 장애 격리. SemaphoreBulkhead와 ThreadPoolBulkhead.

### BulkheadBasicTest (SemaphoreBulkhead)

| 테스트 | 증명 |
|--------|------|
| 동시 25건중 20건 통과하고 5건 거절된다 | maxConcurrentCalls 초과 → BulkheadFullException |
| maxWaitDuration 0이면 거절된 요청은 즉시 완료된다 | fail-fast: 거절 응답 500ms 이내 |
| maxWaitDuration이 0보다 크면 대기 후 통과한다 | 슬롯 반환 대기 → 거절 없이 전부 통과 |
| Bulkhead 바깥 CB 안쪽이면 거절이 CB 실패로 집계되지 않는다 | Bulkhead 거절은 CB에 도달 안 함 |
| 슬롯 반환 후 대기 중이던 요청이 통과한다 | 세마포어 슬롯 풀 반환 메커니즘 명시적 검증 |
| CB 바깥 Bulkhead 안쪽이면 거절이 CB 실패로 집계된다 | 잘못된 순서: 동시성 포화가 장애로 오인 |
| CB OPEN 상태에서 Bulkhead 슬롯은 잡았다가 즉시 반환된다 | CB 거절이 동기적 → 슬롯 점유 무해 |

### ThreadPoolBulkheadTest

| 테스트 | 증명 |
|--------|------|
| maxThreadPoolSize와 queueCapacity 초과시 거절된다 | 스레드 2 + 큐 2 = 최대 4건, 초과 시 BulkheadFullException |
| queueCapacity 0이면 스레드풀 가득시 즉시 거절된다 | 큐 없음 → fail-fast |
| 큐 대기 중이던 요청이 스레드 반환 후 실행된다 | BlockingQueue → 스레드 비면 자동 실행 |
| ThreadPoolBulkhead는 전용 스레드풀에서 실행된다 | 호출자 스레드와 다름 → ThreadLocal 유실 |
| ThreadPoolBulkhead 바깥 CB 안쪽이면 거절이 CB에 영향주지 않는다 | SemaphoreBulkhead와 동일 원리 |

---

## ratelimiter/

일정 시간 동안 허용 가능한 호출 수 제한.

### RateLimiterBasicTest

| 테스트 | 증명 |
|--------|------|
| limitForPeriod 초과시 RequestNotPermitted 발생한다 | 기본 차단 동작 |
| timeoutDuration이 0보다 크면 허용량 갱신까지 대기 후 통과한다 | 대기 메커니즘 (Bulkhead maxWaitDuration과 유사) |
| timeoutDuration 0이면 즉시 거절된다 | fail-fast 동작 |
| limitRefreshPeriod 경과 후 허용량이 갱신된다 | 주기적 리셋 메커니즘 |
| 동시 요청에서 정확히 limitForPeriod만 통과한다 | AtomicInteger 기반 동시성 보장 |
| RateLimiter 바깥 CB 안쪽이면 거절이 CB 실패로 집계되지 않는다 | 올바른 순서: 거절은 CB 도달 전 |
| CB 바깥 RateLimiter 안쪽이면 거절이 CB 실패로 집계된다 | 잘못된 순서: rate limit 초과가 CB 오염 |

---

## timelimiter/

CompletableFuture 기반 비동기 호출의 타임아웃 제어. readTimeout과는 다른 레이어.

### TimeLimiterBasicTest

| 테스트 | 증명 |
|--------|------|
| timeoutDuration 초과시 TimeoutException 발생한다 | 비즈니스 레벨 타임아웃 |
| 응답이 timeoutDuration 이내이면 성공한다 | 타임아웃 이내 → 정상 결과 |
| cancelRunningFuture true이면 타임아웃시 Future가 취소된다 | 리소스 해제 보장 |
| cancelRunningFuture false이면 타임아웃 후에도 작업이 계속 실행된다 | 부수효과 보호 (DB 쓰기 등) |
| TimeLimiter 타임아웃이 CB 실패로 집계된다 | TimeoutException → CB 실패 |
| readTimeout이 TimeLimiter보다 짧으면 ResourceAccessException이 먼저 발생한다 | 네트워크 타임아웃 우선 |
| TimeLimiter가 readTimeout보다 짧으면 TimeoutException이 먼저 발생한다 | 비즈니스 타임아웃 우선 |

---

## combination/

3단~5단 조합의 실제 동작과 순서 함정.

### FullChainTest

| 테스트 | 증명 |
|--------|------|
| Bulkhead CB Retry 3단 조합에서 각 레이어가 올바르게 동작한다 | 전부 실패 → CB OPEN → 후속 즉시 거절, Retry 재시도는 CB 1건 |
| 삼단 조합에서 Retry 성공시 CB 성공 집계되고 Bulkhead 슬롯 반환된다 | Retry가 실패 흡수 → CB/Bulkhead 모두 영향 없음 |

### FiveLayerChainTest

| 테스트 | 증명 |
|--------|------|
| 풀체인 5단에서 정상 요청이 모든 레이어를 통과한다 | Retry→CB→RL→TL→BH 전부 통과 |
| 풀체인 5단에서 RateLimiter 거절이 CB를 오염시키는 함정을 증명한다 | 기본 Aspect 순서의 함정: RL이 CB 안쪽 |
| 풀체인 5단에서 장애시 Retry와 CB가 올바르게 연동한다 | CB OPEN 후 하위 레이어 도달 안 함 |

### RetryBulkheadSlotTest

| 테스트 | 증명 |
|--------|------|
| Bulkhead 바깥 Retry 안쪽이면 재시도가 슬롯을 추가 점유하지 않는다 | 같은 슬롯 안에서 재시도 |
| Retry 바깥 Bulkhead 안쪽이면 재시도가 슬롯을 경쟁한다 | 재시도마다 슬롯 새로 확보 → BulkheadFullException 위험 |
| CB OPEN시 retryExceptions에 CallNotPermittedException이 없으면 즉시 실패한다 | 올바른 동작: CB 차단 존중 |
| CB OPEN시 retryExceptions에 CallNotPermittedException을 넣으면 무의미한 재시도가 발생한다 | 오작동: CB 차단을 Retry가 무력화 |

---

## timeout/

네트워크 타임아웃의 기본 동작. 비즈니스 레벨 타임아웃은 [timelimiter/](src/test/java/com/example/resilience/timelimiter/README.md) 참조.

### TimeoutTest

| 테스트 | 증명 |
|--------|------|
| TIMEOUT에서 readTimeout 초과시 ResourceAccessException 발생 | 서버 무응답 → readTimeout 보호 |
| SLOW 응답이 readTimeout 이내이면 성공한다 | 느려도 timeout 이내면 정상 |
| SLOW 응답이 readTimeout을 초과하면 ResourceAccessException 발생 | readTimeout < 응답시간 → 실패 |
