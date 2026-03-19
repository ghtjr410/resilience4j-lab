# resilience4j-lab

**Resilience4j의 설정 함정과 데코레이터 순서를 실제 결제 Mock 서버로 증명하는 120개+ 학습 테스트.**

"문서에서 읽은 것"과 "실제로 돌려본 것"의 차이를 테스트 코드로 보여줍니다.
모든 테스트는 Docker 컨테이너 위의 Mock PG 서버에 실제 HTTP 요청을 보내며, 각 테스트 이름이 곧 증명 명제입니다.

---

## 목차

- [시작하기](#시작하기)
- [테스트 인프라 구조](#테스트-인프라-구조)
- [학습 순서 가이드](#학습-순서-가이드)
- [테스트 목록](#테스트-목록)
  - [1. CircuitBreaker](#1-circuitbreaker--서킷브레이커)
  - [2. Retry](#2-retry--재시도)
  - [3. Bulkhead](#3-bulkhead--동시성-격리)
  - [4. RateLimiter](#4-ratelimiter--호출-빈도-제한)
  - [5. TimeLimiter](#5-timelimiter--비동기-타임아웃)
  - [6. Timeout](#6-timeout--네트워크-타임아웃)
  - [7. Combination](#7-combination--데코레이터-조합)

---

## 시작하기

### 필요 환경

- **Java 21** 이상
- **Docker** 실행 중 (Testcontainers가 Mock 서버 컨테이너를 자동으로 띄웁니다)

### 실행

```bash
git clone https://github.com/ghtjr410/resilience4j-lab.git
cd resilience4j-lab

# 전체 테스트 실행 (약 5분)
./gradlew test

# 특정 테스트 클래스만 실행
./gradlew test --tests "com.example.resilience.circuitbreaker.CircuitBreakerBasicTest"

# 특정 패키지(주제)만 실행
./gradlew test --tests "com.example.resilience.retry.*"
```

> 처음 실행 시 Docker Hub에서 `ghtjr410/mock-toss` 이미지를 pull합니다.
> 이후에는 로컬 캐시를 사용하므로 빠르게 시작됩니다.

---

## 테스트 인프라 구조

```
┌─────────────────────────────────────────────────┐
│  테스트 코드 (JUnit 5)                            │
│  ┌──────────────────┐  ┌──────────────────────┐  │
│  │ Resilience4j      │  │ PaymentClient        │  │
│  │ (CB, Retry, ...)  │→│ (HTTP 호출)           │  │
│  └──────────────────┘  └──────────┬───────────┘  │
└───────────────────────────────────┼──────────────┘
                                    │ HTTP
                     ┌──────────────▼──────────────┐
                     │  mock-toss (Docker 컨테이너)  │
                     │  ┌────────────────────────┐  │
                     │  │ 카오스 모드 엔진          │  │
                     │  │ NORMAL / DEAD / SLOW    │  │
                     │  │ TIMEOUT / PARTIAL       │  │
                     │  └────────────────────────┘  │
                     └─────────────────────────────┘
```

- **PaymentClient**: Spring RestTemplate 기반 HTTP 클라이언트. `confirm()`, `getPayment()`, `cancel()` 메서드 제공.
- **mock-toss**: 토스페이먼츠 결제 API를 재현하는 Mock 서버. 카오스 모드로 장애를 시뮬레이션.
- **ExampleTestBase**: 모든 테스트의 부모 클래스. Testcontainers로 컨테이너 기동, 매 테스트 전 서버 상태 초기화.
- **TestLogger**: Resilience4j 이벤트(성공/실패/거절/상태전이)를 실시간으로 콘솔에 출력하는 유틸리티.

---

## 학습 순서 가이드

처음이라면 아래 순서를 따라가세요. 각 주제 폴더에 상세한 `README.md`가 있습니다.

| 순서 | 주제 | 핵심 질문 | 테스트 수 |
|:----:|------|----------|:---------:|
| 1 | **CircuitBreaker** | 언제 서킷이 열리고, 언제 닫히는가? | 39개 |
| 2 | **Retry** | 뭘 재시도하고, 뭘 재시도하면 안 되는가? | 22개 |
| 3 | **Bulkhead** | 동시 요청을 어떻게 격리하는가? | 12개 |
| 4 | **RateLimiter** | 초당 호출 수를 어떻게 제한하는가? | 7개 |
| 5 | **TimeLimiter** | readTimeout과 뭐가 다른가? | 7개 |
| 6 | **Timeout** | 네트워크 타임아웃은 언제 발생하는가? | 3개 |
| 7 | **Combination** | 데코레이터 순서가 왜 중요한가? | 7개 |

> 각 주제의 상세 설명은 `src/test/java/com/example/resilience/{주제}/README.md` 참조.

---

## 테스트 목록

### 1. CircuitBreaker — 서킷브레이커

장애를 감지하면 요청을 차단하고, 복구되면 다시 허용하는 상태 머신.

```
CLOSED (정상) ──실패율 초과──→ OPEN (차단) ──대기시간 경과──→ HALF_OPEN (시험) ──→ CLOSED 또는 OPEN
```

#### CircuitBreakerBasicTest — 기본 상태 전이

> 서킷이 열리는 조건과 닫히는 조건을 검증합니다.

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| DEAD 모드에서 모든 요청 실패시 서킷이 OPEN으로 전환된다 | 실패율 100% → CLOSED에서 OPEN으로 전이, 이후 요청은 서버에 가지 않고 즉시 `CallNotPermittedException` |
| PARTIAL FAILURE 30%이면 실패율 50% 미만으로 CLOSED 유지 | 실패율이 threshold(50%) 미만이면 서킷은 열리지 않음 |
| PARTIAL FAILURE 90%이면 실패율 50% 초과로 OPEN 전환 | 실패율이 threshold를 넘으면 서킷이 열림 |
| recordResult로 예외 없는 응답도 실패로 집계할 수 있다 | HTTP 200이지만 `status: IN_PROGRESS` → 결과값 기반 실패 판정 |
| recordResult 조건 불일치시 성공으로 집계된다 | `status: DONE` → predicate 불일치 → 성공 처리 |

#### CircuitBreakerRecoveryTest — 복구 흐름

> OPEN에서 어떻게 CLOSED로 돌아오는지, HALF_OPEN의 역할은 무엇인지 검증합니다.

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| HALF OPEN에서 성공하면 CLOSED로 복구된다 | OPEN → HALF_OPEN → CLOSED 복구 흐름의 전체 경로 |
| HALF OPEN에서 실패하면 OPEN으로 재진입한다 | 복구 시도 실패 → 다시 차단 |
| CLOSED 복구 후 슬라이딩 윈도우가 초기화된다 | 복구 후 이전 실패 이력이 사라짐 — 40% 실패해도 CLOSED 유지 |
| HALF OPEN에서 실패율이 threshold 미만이면 CLOSED로 복구 | 33% < 50% → 복구 성공 |
| HALF OPEN에서 실패율이 threshold 이상이면 OPEN으로 재진입 | 66% > 50% → 다시 차단 |

#### CircuitBreakerResetTest — 수동 리셋

> 운영 중 긴급 복구가 필요할 때 `reset()` 호출의 효과를 검증합니다.

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| OPEN에서 reset 호출시 CLOSED로 복구되고 메트릭이 초기화된다 | 긴급 복구: 대기시간 없이 즉시 CLOSED |
| FORCED OPEN에서 reset 호출시 정상 요청이 통과한다 | PG 점검 후 즉시 복구 |
| reset 후 이전 실패 이력이 이월되지 않는다 | sliding window까지 완전 초기화 |

#### SlowCallDetectionTest — 느린 응답 감지

> timeout과 slowCall의 차이를 검증합니다. slowCall은 응답은 받지만 "느리다"는 사실을 기록합니다.

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| SLOW 전부이면 slowCallRate 100%로 OPEN | 예외 없이도 느린 응답만으로 서킷이 열림 |
| slowCall은 요청을 끊지 않고 느린 성공도 장애 전조로 집계한다 | timeout과 달리 응답은 정상 반환, 집계만 됨 |
| slowCall과 failure 복합시 둘 중 하나라도 threshold 넘으면 OPEN | failureRate와 slowCallRate는 독립적으로 판정 |
| HALF OPEN에서 느린 성공만으로도 OPEN 재진입한다 | 성공해도 느리면 복구가 안 됨 |

#### HalfOpenBehaviorTest — HALF_OPEN 상세 동작

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| permittedNumberOfCalls 초과 요청은 거절된다 | HALF_OPEN에서 허용된 슬롯 초과 시 즉시 차단 |
| maxWaitDurationInHalfOpenState 기본값 0이면 무한 대기 | 느린 요청이 슬롯을 점유하면 HALF_OPEN에 영원히 갇힘 |
| maxWaitDurationInHalfOpenState 설정시 시간 초과하면 OPEN 복귀 | 타임아웃으로 강제 OPEN 복귀 |

#### ExceptionHandlingTest — 예외 필터링

> 어떤 예외를 실패로 집계하고, 어떤 예외를 무시할지 결정합니다.

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| 기본 동작에서 모든 예외가 실패로 집계된다 | 403 비즈니스 에러도 실패 집계 → 서킷 오진의 원인 |
| ignoreExceptions 설정시 특정 예외가 무시된다 | 비즈니스 예외를 집계에서 제외하는 방법 |
| recordExceptions에 지정한 예외만 실패로 기록된다 | 화이트리스트 방식 실패 기록 |
| recordFailurePredicate로 5xx만 실패 판단 | 커스텀 조건: 서버 에러만 실패로 |
| ignoreExceptions가 recordExceptions보다 우선한다 | 양쪽에 있으면 ignore가 이김 |

#### ExceptionInheritanceTest — 예외 계층 상속

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| 부모 예외 지정시 자식 예외도 실패로 집계된다 | instanceof 기반 — 예외 계층 전체가 대상 |
| 부모 예외 지정시 4xx 자식도 실패로 집계되어 오진 발생 | `HttpStatusCodeException` → 4xx/5xx 구분 불가 |
| 부모 record + 자식 ignore로 선별 제외 | 넓게 잡고 좁게 빼는 패턴 |

#### IgnoreInHalfOpenTest — HALF_OPEN에서 ignore 예외

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| 모든 permitted가 ignore되면 판정 불가로 HALF_OPEN에 머문다 | 슬롯은 소모했지만 판정할 결과가 없음 |
| ignore와 성공이 섞이면 성공만으로 판정된다 | ignore는 "없었던 것" 처리 |
| ignore와 실패가 섞이면 실패만으로 판정된다 | ignore 제외 후 실패율 재계산 |

#### WaitIntervalFunctionTest — OPEN 대기시간 전략

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| exponential backoff로 대기 시간이 실패 반복마다 증가 | 1초 → 2초 → 4초 (지수 증가) |
| 고정 waitDuration은 반복해도 동일 | 대비: 매번 같은 시간 대기 |

#### CircuitBreakerTrapTest — 설정 함정 7가지

> Before(함정에 빠진 코드) / After(올바른 코드) 쌍으로 구성.
> **실무에서 가장 많이 실수하는 설정들입니다.**

| 함정 | 무엇이 문제인가 |
|------|---------------|
| 함정1: `minimumNumberOfCalls` 기본값 100 | 10건 실패해도 서킷이 안 열림 — 100건 채워야 판정 시작 |
| 함정2: `automaticTransitionFromOpenToHalfOpenEnabled` 기본값 false | 호출이 없으면 OPEN에서 영원히 빠져나올 수 없음 |
| 함정3: `ignoreExceptions` 미설정 | 403 비즈니스 에러가 실패로 집계 → 서킷 오진 |
| 함정4: `slidingWindowSize` 기본값 100 | 장애 감지가 100건 후에야 시작 → 감지 지연 |
| 함정5: `slidingWindowSize < minimumNumberOfCalls` | 자동 보정되어 의도보다 빨리 서킷이 열림 |
| 함정6: `slowCallDurationThreshold` > readTimeout | readTimeout이 먼저 터져서 slowCall이 아닌 failure로 집계 |
| 함정7: `maxWaitDurationInHalfOpenState` 기본값 0 | HALF_OPEN 무한 대기 → 복구 불가 |

#### TimeBasedWindowTest — 시간 기반 윈도우

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| 시간 경과 후 이전 실패가 만료된다 | COUNT_BASED와의 핵심 차이: 오래된 실패가 자동으로 사라짐 |

#### SpecialStateTest — 수동 상태 제어

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| FORCED_OPEN — 모든 요청 거부 | PG 점검 시 수동으로 서킷 차단 |
| DISABLED — 항상 허용, 상태 전이 없음 | 서킷 비활성화 (메트릭도 없음) |
| METRICS_ONLY — 메트릭만 수집, 차단 안 함 | 프로덕션 검증용: 차단 없이 관찰만 |

---

### 2. Retry — 재시도

일시적 실패에 대해 자동으로 재시도. 단, 잘못된 설정은 장애를 악화시킵니다.

#### RetryBasicTest — 기본 동작

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| 첫 시도 실패 후 재시도에서 성공한다 | DEAD → NORMAL 전환으로 재시도 성공 |
| 3회 전부 실패하면 최종 예외가 발생한다 | maxAttempts 소진 시 예외 전파 |
| TIMEOUT에서 ResourceAccessException으로 재시도한다 | 네트워크 타임아웃도 재시도 대상 |
| retryExceptions에 없는 예외는 즉시 실패한다 | 화이트리스트에 없으면 재시도 안 함 |
| 비즈니스 에러 403은 재시도하지 않는다 | 재시도해도 결과가 같은 에러는 제외 |
| waitDuration 설정시 재시도 간 대기 | 실제 대기 시간 검증 |
| maxAttempts는 초기 호출 포함 총 시도 횟수 | maxAttempts(3) = 초기 1회 + 재시도 2회 |
| failAfterMaxAttempts는 예외 기반에는 미적용 | true로 설정해도 원래 예외가 전파됨 |
| failAfterMaxAttempts true + 결과 기반 → MaxRetriesExceededException | 결과 기반에서만 래핑 예외 발생 |

#### RetryExceptionFilterTest — 예외별 재시도 제어

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| retryExceptions에 포함된 예외만 재시도 | 화이트리스트 방식 |
| 비즈니스 에러 403은 재시도 제외 | HttpClientErrorException은 리스트에 없으므로 즉시 전파 |

#### RetryIgnoreExceptionsTest — 재시도 금지 예외

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| ignoreExceptions에 지정된 예외는 즉시 전파 | 블랙리스트 방식 재시도 금지 |
| ignore와 retry에 동일 예외 → ignore 우선 | CircuitBreaker와 동일 원리 |
| 부모 예외 ignore시 자식도 무시 | instanceof 기반 상속 |

#### RetryExceptionPredicateTest — 조건부 재시도

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| retryOnException Predicate로 커스텀 조건 적용 | 리스트가 아닌 함수로 판단 |
| Predicate false이면 즉시 실패 | 조건 불일치 → 재시도 안 함 |
| HTTP 상태 코드별 세밀한 제어 | 5xx만 재시도, 4xx는 즉시 전파 |

#### RetryBackoffTest — 대기 전략

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| exponentialBackoff → 대기시간 지수 증가 | 1초 → 2초 → 4초 |
| exponentialRandomBackoff → jitter 적용 | thundering herd 방지 |

#### RetryIntervalBiFunctionTest — 동적 대기시간

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| 예외 종류별 대기시간 동적 변경 | timeout → 3초, 500 에러 → 1초 |
| 500 에러시 짧은 대기 | 일시적 오류는 빠르게 재시도 |
| 결과 기반 재시도에서도 동작 | Either.right → 폴링 간격 설정 |

#### RetryResultPredicateTest — 결과값 기반 재시도

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| status가 IN_PROGRESS이면 예외 없이도 재시도 | HTTP 200이지만 비즈니스 미완료 → 재시도 |
| 모든 시도에서 매칭시 마지막 결과 반환 | 예외가 아닌 마지막 결과가 반환됨 |
| retryOnResult + retryExceptions 복합 사용 | 예외와 결과값 둘 다 재시도 트리거 |

#### RetryWithCircuitBreakerTest — CB와의 조합 순서

> **가장 중요한 테스트.** 순서를 바꾸면 완전히 다른 결과가 나옵니다.

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| Retry(바깥) → CB(안) → 1건 요청이 CB에 3건으로 집계 | **잘못된 순서**: 재시도마다 CB 실패 누적 |
| CB(바깥) → Retry(안) → 1건 요청이 CB에 1건으로 집계 | **올바른 순서**: CB는 최종 결과만 봄 |
| CB(바깥) → Retry 내부 실패 후 성공 → CB에 성공 1건 | Retry 내부 실패가 CB를 오염시키지 않음 |

---

### 3. Bulkhead — 동시성 격리

동시 호출 수를 제한하여 한 서비스의 장애가 다른 서비스로 전파되지 않게 합니다.

#### BulkheadBasicTest — SemaphoreBulkhead

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| 동시 25건 중 20건 통과, 5건 거절 | maxConcurrentCalls 초과 → `BulkheadFullException` |
| maxWaitDuration 0이면 즉시 거절 | fail-fast: 대기 없이 즉시 에러 |
| maxWaitDuration > 0이면 대기 후 통과 | 슬롯 반환 대기 → 전부 통과 |
| Bulkhead(바깥) → CB(안) → 거절이 CB 실패로 안 잡힘 | **올바른 순서**: 거절은 CB에 도달 전 |
| 슬롯 반환 후 대기 요청이 통과한다 | 세마포어 슬롯 풀 반환 메커니즘 |
| CB(바깥) → Bulkhead(안) → 거절이 CB 실패로 집계 | **잘못된 순서**: 동시성 포화가 장애로 오인 |
| CB OPEN 상태에서 Bulkhead 슬롯 즉시 반환 | CB 거절은 동기적 → 슬롯 점유 시간 무해 |

#### ThreadPoolBulkheadTest — ThreadPoolBulkhead

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| maxThreadPoolSize + queueCapacity 초과시 거절 | 스레드 2 + 큐 2 = 최대 4건, 초과 시 거절 |
| queueCapacity 0이면 스레드풀 가득시 즉시 거절 | 큐 없음 → fail-fast |
| 큐 대기 요청이 스레드 반환 후 실행 | BlockingQueue → 스레드 비면 자동 실행 |
| 전용 스레드풀에서 실행된다 | 호출자 스레드와 다름 → **ThreadLocal 유실 주의** |
| ThreadPoolBulkhead(바깥) → CB(안) → 거절이 CB에 무영향 | SemaphoreBulkhead와 동일 원리 |

---

### 4. RateLimiter — 호출 빈도 제한

일정 시간 동안 허용 가능한 호출 수를 제한합니다.

#### RateLimiterBasicTest

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| limitForPeriod 초과시 `RequestNotPermitted` 발생 | 기본 차단 동작 |
| timeoutDuration > 0이면 허용량 갱신까지 대기 후 통과 | 대기 메커니즘 |
| timeoutDuration 0이면 즉시 거절 | fail-fast |
| limitRefreshPeriod 경과 후 허용량이 갱신된다 | 주기적 리셋 |
| 동시 요청에서 정확히 limitForPeriod만 통과 | 동시성 보장 검증 |
| RateLimiter(바깥) → CB(안) → 거절이 CB에 무영향 | **올바른 순서** |
| CB(바깥) → RateLimiter(안) → 거절이 CB 실패로 집계 | **잘못된 순서** |

---

### 5. TimeLimiter — 비동기 타임아웃

`CompletableFuture` 기반 비동기 호출에 비즈니스 레벨 타임아웃을 적용합니다.
HTTP readTimeout과는 다른 레이어에서 동작합니다.

#### TimeLimiterBasicTest

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| timeoutDuration 초과시 `TimeoutException` 발생 | 비즈니스 레벨 타임아웃 |
| timeoutDuration 이내이면 성공 | 정상 경로 |
| cancelRunningFuture=true → 타임아웃시 Future 취소 | 리소스 해제 보장 |
| cancelRunningFuture=false → 타임아웃 후에도 작업 계속 | 부수효과 보호 (DB 쓰기 등) |
| TimeLimiter 타임아웃이 CB 실패로 집계 | TimeoutException → CB 실패 |
| readTimeout < TimeLimiter → `ResourceAccessException` 먼저 | 네트워크 타임아웃이 우선 |
| TimeLimiter < readTimeout → `TimeoutException` 먼저 | 비즈니스 타임아웃이 우선 |

---

### 6. Timeout — 네트워크 타임아웃

HTTP readTimeout의 기본 동작을 확인합니다. 비즈니스 레벨 타임아웃은 [TimeLimiter](#5-timelimiter--비동기-타임아웃) 참조.

#### TimeoutTest

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| TIMEOUT 모드에서 readTimeout 초과시 `ResourceAccessException` | 서버 무응답 → readTimeout 보호 |
| SLOW 응답이 readTimeout 이내이면 성공 | 느려도 timeout 이내면 정상 |
| SLOW 응답이 readTimeout 초과하면 실패 | readTimeout < 응답시간 → 실패 |

---

### 7. Combination — 데코레이터 조합

**실무에서 가장 중요한 부분.** 데코레이터 순서가 바뀌면 동작이 완전히 달라집니다.

#### 올바른 순서 (바깥 → 안쪽)

```
Bulkhead  →  CircuitBreaker  →  Retry  →  서버 호출
(격리)        (차단)              (재시도)
```

#### FullChainTest — 3단 조합

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| Bulkhead → CB → Retry 3단 조합 정상 동작 | 전부 실패 → CB OPEN → 후속 즉시 거절, Retry 재시도는 CB에 1건 |
| Retry 성공시 CB 성공 집계 + Bulkhead 슬롯 반환 | Retry가 실패를 흡수 → CB/Bulkhead 모두 영향 없음 |

#### FiveLayerChainTest — 5단 풀체인

```
Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → 서버
```

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| 정상 요청이 모든 레이어를 통과한다 | 5단 전부 통과 확인 |
| RateLimiter 거절이 CB를 오염시키는 함정 | 기본 Aspect 순서의 함정: RL이 CB 안쪽이면 거절이 실패로 집계 |
| 장애시 Retry와 CB가 올바르게 연동한다 | CB OPEN 후 하위 레이어에 도달하지 않음 |

#### RetryBulkheadSlotTest — Retry + Bulkhead 슬롯 경쟁

| 테스트 | 무엇을 증명하는가 |
|--------|------------------|
| Bulkhead(바깥) → Retry(안) → 재시도가 슬롯 추가 점유 안 함 | 같은 슬롯 안에서 재시도 |
| Retry(바깥) → Bulkhead(안) → 재시도마다 슬롯 경쟁 | 재시도할 때마다 새 슬롯 필요 → `BulkheadFullException` 위험 |
| CB OPEN시 retryExceptions에 CallNotPermittedException 없으면 즉시 실패 | **올바른 동작**: CB 차단을 존중 |
| CB OPEN시 retryExceptions에 CallNotPermittedException 넣으면 무의미한 재시도 | **오작동**: CB 차단을 Retry가 무력화 — 절대 하면 안 됨 |
