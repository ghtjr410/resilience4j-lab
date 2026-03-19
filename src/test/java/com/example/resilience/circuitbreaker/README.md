# CircuitBreaker 학습 테스트

서킷브레이커의 상태 전이, slowCall 감지, HALF-OPEN 동작,
예외 처리 전략, 설정 함정, 그리고 TestLogger 이벤트 타이밍 문제.

---

## CircuitBreakerBasicTest

CLOSED → OPEN 전이의 기본 메커니즘.

### 100% 실패 → OPEN 전환

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker<br/>(CLOSED)
    participant Mock as Mock서버<br/>(DEAD)

    Note over CB: slidingWindowSize=5<br/>failureRateThreshold=50%<br/>minimumNumberOfCalls=5

    loop 5회 호출
        Test->>CB: call()
        CB->>Mock: GET /confirm
        Mock-->>CB: 500 Error
        CB->>CB: 실패 기록
    end

    Note over CB: failureRate=100% > 50%
    CB->>CB: CLOSED → OPEN

    Test->>CB: call()
    CB-->>Test: CallNotPermittedException<br/>(요청이 서버에 도달하지 않음)
```

### 경계값: 실패율이 threshold 미만이면 CLOSED 유지

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker<br/>(CLOSED)

    Note over CB: slidingWindowSize=100<br/>failureRateThreshold=50%

    Note over Test: PARTIAL_FAILURE 30%로<br/>100건 요청

    loop 100회 호출 (~70회 성공 + ~30회 실패)
        Test->>CB: call()
        CB->>CB: 결과 기록
    end

    Note over CB: failureRate≈30% < 50%
    Note over CB: CLOSED 유지

    Note over Test: slidingWindowSize=100이면<br/>30%±~4.6% 분산<br/>50%를 넘을 확률 ≈ 0
```

### recordResult: 예외 없는 응답도 실패로 집계

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant PG as PG API

    Note over CB: recordResult:<br/>status=="IN_PROGRESS"

    Test->>CB: call()
    CB->>PG: 요청
    PG-->>CB: 200 OK<br/>{"status": "IN_PROGRESS"}

    Note over CB: 예외 없음, 하지만<br/>recordResult predicate 매칭<br/>→ 실패로 집계

    Note over CB: recordExceptions: 예외 기반 판정<br/>recordResult: 결과값 기반 판정<br/>두 축이 독립적으로 동작
```

---

## CircuitBreakerRecoveryTest

OPEN → HALF_OPEN → CLOSED/OPEN 복구 흐름.

### 복구 성공

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버

    Note over CB: CLOSED 상태

    rect rgb(255, 230, 230)
        Note over Mock: DEAD 모드
        loop 5회 실패
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 500 Error
        end
        Note over CB: CLOSED → OPEN
    end

    Note over Test: waitDurationInOpenState 대기

    rect rgb(230, 255, 230)
        Note over Mock: NORMAL 모드로 전환
        Note over CB: OPEN → HALF_OPEN
        loop permittedNumberOfCalls(2)회
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 200 OK
        end
        Note over CB: HALF_OPEN → CLOSED (복구 완료)
    end
```

### 복구 실패 → 다시 OPEN

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버

    Note over CB: OPEN → HALF_OPEN 전이 후

    rect rgb(255, 230, 230)
        Note over Mock: 여전히 DEAD
        loop permittedNumberOfCalls(2)회
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 500 Error
        end
        Note over CB: HALF_OPEN → OPEN (재차단)
    end
```

### 복구 후 sliding window 리셋

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버

    rect rgb(255, 230, 230)
        Note over Mock: DEAD 모드
        loop 5회 실패
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 500 Error
        end
        Note over CB: CLOSED → OPEN
    end

    rect rgb(230, 255, 230)
        Note over Mock: NORMAL 모드로 전환
        Note over CB: OPEN → HALF_OPEN
        loop 3회 성공
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 200 OK
        end
        Note over CB: HALF_OPEN → CLOSED (복구)
    end

    rect rgb(230, 240, 255)
        Note over CB: sliding window 리셋됨
        loop 2회 실패 + 3회 성공
            Test->>CB: call()
            CB->>CB: 결과 기록
        end
        Note over CB: failureRate=40% < 50%<br/>→ CLOSED 유지
        Note over CB: 이전 실패 이력이<br/>이월되지 않음을 증명
    end
```

### HALF_OPEN 경계값: threshold 미만 → CLOSED

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker<br/>(HALF_OPEN)
    participant Mock as Mock서버

    Note over CB: permittedCalls=3<br/>failureRateThreshold=50%

    Test->>CB: call("system_error")
    CB->>Mock: GET /confirm
    Mock-->>CB: 500 Error
    CB->>CB: 실패 기록 (1/3)

    loop 2회 성공
        Test->>CB: call()
        CB->>Mock: GET /confirm
        Mock-->>CB: 200 OK
    end

    Note over CB: failureRate=33% < 50%
    Note over CB: HALF_OPEN → CLOSED (복구)
```

### HALF_OPEN 경계값: threshold 이상 → OPEN

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker<br/>(HALF_OPEN)
    participant Mock as Mock서버

    Note over CB: permittedCalls=3<br/>failureRateThreshold=50%

    loop 2회 실패
        Test->>CB: call("system_error")
        CB->>Mock: GET /confirm
        Mock-->>CB: 500 Error
    end

    Test->>CB: call()
    CB->>Mock: GET /confirm
    Mock-->>CB: 200 OK

    Note over CB: failureRate=66% > 50%
    Note over CB: HALF_OPEN → OPEN (재진입)
```

---

## SlowCallDetectionTest

slowCall은 timeout과 다르다 — 요청을 끊지 않고, 느린 성공도 장애 전조로 집계한다.

### slowCall vs timeout

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버<br/>(SLOW 3초)

    Note over CB: slowCallDurationThreshold=2s<br/>slowCallRateThreshold=50%

    Test->>CB: call()
    CB->>Mock: GET /confirm
    Note over Mock: 3초 대기 후 응답
    Mock-->>CB: 200 OK (3초 소요)

    Note over CB: elapsed(3s) > threshold(2s)<br/>→ "느린 성공"으로 기록
    Note over CB: 응답은 정상 반환됨<br/>(timeout처럼 끊지 않음)
```

### slowCall + failure 복합

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버

    Note over CB: failureRateThreshold=60%<br/>slowCallRateThreshold=60%<br/>slidingWindowSize=5

    rect rgb(255, 245, 230)
        Note over Mock: SLOW 모드
        loop 3회 (느린 성공)
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 200 OK (slow)
        end
    end

    rect rgb(230, 255, 230)
        Note over Mock: NORMAL 모드
        loop 2회 (정상 성공)
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 200 OK (fast)
        end
    end

    Note over CB: failureRate=0% < 60% (실패 없음)<br/>slowCallRate=60% >= 60%
    Note over CB: slowCallRate만으로 OPEN 전환
```

### HALF_OPEN에서 느린 성공 → OPEN 재진입

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버<br/>(SLOW 2초)

    Note over CB: slowCallRateThreshold=50%<br/>permittedCalls=3

    rect rgb(255, 230, 230)
        Note over CB: Phase 1: SLOW → OPEN
        loop 3회 (느린 성공)
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 200 OK (2초)
        end
        Note over CB: slowCallRate=100% > 50%<br/>→ OPEN
    end

    Note over Test: waitDuration 대기
    Note over CB: OPEN → HALF_OPEN

    rect rgb(255, 245, 230)
        Note over CB: Phase 2: HALF_OPEN에서도 느림
        loop 3회 (느린 성공)
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 200 OK (2초)
        end
        Note over CB: slowCallRate=100% > 50%<br/>→ OPEN 재진입
        Note over CB: 성공해도 느리면<br/>복구가 안 된다
    end
```

---

## HalfOpenBehaviorTest

HALF_OPEN 상태의 제약 조건과 함정.

### permittedCalls 초과 → 거절

```mermaid
sequenceDiagram
    participant T1 as Thread-1
    participant T2 as Thread-2
    participant T3 as Thread-3
    participant CB as CircuitBreaker<br/>(HALF_OPEN)
    participant Mock as Mock서버<br/>(SLOW 2초)

    Note over CB: permittedNumberOfCallsInHalfOpenState=2

    T1->>CB: call()
    CB->>Mock: GET /confirm (slot 1/2)
    T2->>CB: call()
    CB->>Mock: GET /confirm (slot 2/2)

    T3->>CB: call()
    CB-->>T3: CallNotPermittedException<br/>(허용 슬롯 초과)

    Mock-->>CB: 200 OK (T1)
    Mock-->>CB: 200 OK (T2)
    Note over CB: HALF_OPEN → CLOSED
```

### maxWaitDurationInHalfOpenState=0 → 무한 대기

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker<br/>(HALF_OPEN)
    participant Mock as Mock서버<br/>(SLOW 3초)

    Note over CB: permittedCalls=2<br/>maxWaitDuration=0 (기본값)

    Test->>CB: call() (slot 1/2)
    CB->>Mock: GET /confirm
    Note over Mock: 3초 대기 중...

    Test->>CB: call() (slot 2/2)
    CB->>Mock: GET /confirm
    Note over Mock: 3초 대기 중...

    Note over CB: 2개 슬롯 모두 사용중<br/>추가 요청 거절됨
    Note over CB: maxWaitDuration=0 → 타임아웃 없음<br/>느린 요청이 끝날 때까지 무한 대기
    Note over CB: HALF_OPEN에 갇힘
```

---

## ExceptionHandlingTest

어떤 예외를 실패로 집계할 것인가 — 비즈니스 예외 오진 방지.

### 기본 동작: 모든 예외 = 실패 (오진)

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버

    Note over CB: 기본 설정 (예외 필터 없음)<br/>slidingWindowSize=5<br/>failureRateThreshold=50%

    loop 5회
        Test->>CB: call("reject_company")
        CB->>Mock: GET /confirm
        Mock-->>CB: 403 Forbidden
        CB->>CB: 실패로 기록 ← 비즈니스 에러인데!
    end

    Note over CB: failureRate=100%<br/>CLOSED → OPEN
    Note over CB: 서버는 정상인데<br/>비즈니스 거절로 서킷이 열림 (오진)
```

### ignoreExceptions로 해결

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버

    Note over CB: ignoreExceptions=<br/>[HttpClientErrorException]

    loop 5회
        Test->>CB: call("reject_company")
        CB->>Mock: GET /confirm
        Mock-->>CB: 403 Forbidden
        CB->>CB: 무시 (집계 제외)
    end

    Note over CB: 집계된 호출 없음<br/>CLOSED 유지 (정상)
```

### 우선순위: ignoreExceptions > recordExceptions

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker

    Note over CB: recordExceptions=[HttpServerErrorException]<br/>ignoreExceptions=[HttpServerErrorException]

    Test->>CB: call() → 500 Error

    Note over CB: recordExceptions에 있지만<br/>ignoreExceptions에도 있음
    Note over CB: → ignoreExceptions 우선<br/>→ 집계에서 제외
```

---

## CircuitBreakerResetTest

| 테스트 | 증명 |
|--------|------|
| OPEN에서 reset 호출시 CLOSED로 복구되고 메트릭이 초기화된다 | 수동 리셋 — 운영 중 긴급 복구 |
| FORCED OPEN에서 reset 호출시 CLOSED로 복구되고 정상 요청이 통과한다 | PG 점검 후 즉시 복구 |
| reset 후 이전 실패 이력이 이월되지 않는다 | sliding window까지 완전 초기화 |

---

## ExceptionInheritanceTest

| 테스트 | 증명 |
|--------|------|
| recordExceptions에 부모 예외 지정시 자식 예외도 실패로 집계된다 | instanceof 기반 판정 — 예외 계층 상속 |
| 부모 예외 지정시 4xx 자식도 실패로 집계되어 오진이 발생한다 | HttpStatusCodeException → 4xx/5xx 구분 불가 |
| 부모 예외 record와 ignoreExceptions로 자식을 선별적으로 제외한다 | 넓게 record + 좁게 ignore 패턴 |

---

## IgnoreInHalfOpenTest

| 테스트 | 증명 |
|--------|------|
| HALF OPEN에서 모든 permitted가 ignore되면 판정 불가로 HALF OPEN에 머문다 | permitted 소모하되 결과 불포함 → 상태 전이 불가 |
| HALF OPEN에서 ignore와 성공이 섞이면 성공만으로 판정된다 | ignore는 "없었던 것" — 성공만으로 CLOSED |
| HALF OPEN에서 ignore와 실패가 섞이면 실패만으로 판정된다 | ignore 제외 후 실패율 계산 → OPEN 재진입 |

---

## WaitIntervalFunctionTest

| 테스트 | 증명 |
|--------|------|
| exponential backoff로 OPEN 대기 시간이 실패 반복마다 증가한다 | 1차 1초 → 2차 2초 (지수 증가) |
| 고정 waitDuration은 실패 반복해도 대기 시간이 동일하다 | 대비: 고정 vs exponential backoff |

---

## CircuitBreakerTrapTest — 설정 함정 7가지

각 함정마다 "잘못된 설정 → 올바른 설정" Before/After 증명.

### 함정 1: minimumNumberOfCalls 기본값 100

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker

    Note over CB: minimumNumberOfCalls=100 (기본값)<br/>slidingWindowSize=100

    loop 5회 실패
        Test->>CB: call() → 500
        CB->>CB: 실패 기록
    end

    Note over CB: failureRate=100%이지만...<br/>호출 수(5) < minimumNumberOfCalls(100)<br/>→ 실패율 계산 자체를 안 함<br/>→ CLOSED 유지 (서킷 안 열림!)

    Note over Test: 수정: minimumNumberOfCalls=5

    loop 5회 실패
        Test->>CB: call() → 500
    end

    Note over CB: 호출 수(5) >= minimumNumberOfCalls(5)<br/>failureRate=100% > 50%<br/>→ OPEN 전환 (정상 동작)
```

### 함정 2: automaticTransition=false (기본값)

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Timer as 내부 타이머

    rect rgb(255, 230, 230)
        Note over CB: automaticTransition=false (기본값)
        Note over CB: OPEN 상태, waitDuration 경과
        Note over CB: 아무도 호출하지 않으면<br/>OPEN에 영원히 머무름
        Test->>CB: call() ← 이 호출이 와야 전이 발생
        Note over CB: OPEN → HALF_OPEN
    end

    rect rgb(230, 255, 230)
        Note over CB: automaticTransition=true
        Note over CB: OPEN 상태
        Timer->>CB: waitDuration 경과 시 자동 전이
        Note over CB: OPEN → HALF_OPEN<br/>(호출 없이도 전이)
    end
```

### 함정 3: ignoreExceptions 미설정

(ExceptionHandlingTest 섹션 참고)

### 함정 4: slidingWindowSize 기본값 100

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker

    rect rgb(255, 230, 230)
        Note over CB: slidingWindowSize=100 (기본값)<br/>minimumNumberOfCalls=100
        Note over Test: 장애 감지까지 최소 100건 필요<br/>→ 감지 지연
    end

    rect rgb(230, 255, 230)
        Note over CB: slidingWindowSize=5<br/>minimumNumberOfCalls=5
        Note over Test: 5건만에 장애 감지<br/>→ 빠른 대응
    end
```

### 함정 5: slidingWindowSize < minimumNumberOfCalls

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker

    rect rgb(255, 230, 230)
        Note over CB: slidingWindowSize=5<br/>minimumNumberOfCalls=10

        loop 20회 실패
            Test->>CB: call() → 500
        end

        Note over CB: 의도: 10건 후 평가 시작<br/>실제: Resilience4j가 자동으로<br/>minimumNumberOfCalls를 5로 보정!<br/>→ 5건만에 OPEN (의도보다 빨리 열림)
    end

    rect rgb(230, 255, 230)
        Note over CB: slidingWindowSize=10<br/>minimumNumberOfCalls=10
        Note over Test: window >= minCalls이면 정상 동작
    end
```

### 함정 6: slowCallDurationThreshold와 readTimeout 관계

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버<br/>(SLOW 3초)

    rect rgb(255, 230, 230)
        Note over CB: slowCallDurationThreshold=60s (기본값)<br/>readTimeout=5s
        Test->>CB: call()
        CB->>Mock: GET /confirm
        Note over Mock: 3초 후 응답
        Mock-->>CB: 200 OK (3초)
        Note over CB: 3s < 60s → slowCall 아님<br/>readTimeout(5s)보다 threshold가 크면<br/>slowCall 감지 불가
    end

    rect rgb(230, 255, 230)
        Note over CB: slowCallDurationThreshold=2s<br/>readTimeout=5s
        Test->>CB: call()
        CB->>Mock: GET /confirm
        Mock-->>CB: 200 OK (3초)
        Note over CB: 3s > 2s → slowCall 기록<br/>threshold < readTimeout이어야 감지 가능
    end
```

### 함정 7: maxWaitDurationInHalfOpenState=0

(HalfOpenBehaviorTest 섹션 참고)

---

## TimeBasedWindowTest

COUNT_BASED와 TIME_BASED의 핵심 차이 — 시간이 지나면 오래된 호출이 자동으로 만료된다.

### TIME_BASED 윈도우 만료

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버

    Note over CB: slidingWindowType=TIME_BASED<br/>slidingWindowSize=3 (3초)

    rect rgb(255, 230, 230)
        Note over Mock: DEAD 모드
        loop 3회 실패
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 500 Error
        end
        Note over CB: CLOSED → OPEN
    end

    rect rgb(230, 255, 230)
        Note over Mock: NORMAL 모드
        Note over CB: OPEN → HALF_OPEN → CLOSED
    end

    Note over Test: 4초 대기<br/>(3초 윈도우 만료)

    rect rgb(230, 240, 255)
        Note over CB: 윈도우가 비어 있음
        Test->>CB: call("system_error")
        CB->>Mock: GET /confirm
        Mock-->>CB: 500 Error

        loop 2회 성공
            Test->>CB: call()
            CB->>Mock: GET /confirm
            Mock-->>CB: 200 OK
        end

        Note over CB: failureRate=33% < 50%<br/>→ CLOSED 유지
        Note over CB: COUNT_BASED였다면<br/>이전 실패가 남아 있을 수 있음
    end
```

---

## SpecialStateTest

수동 상태 전환 — FORCED_OPEN, DISABLED, METRICS_ONLY.

### FORCED_OPEN: 수동 서킷 차단

```mermaid
sequenceDiagram
    participant Ops as 운영자
    participant CB as CircuitBreaker
    participant Mock as Mock서버

    Ops->>CB: transitionToForcedOpenState()
    Note over CB: FORCED_OPEN

    loop 모든 요청
        Ops->>CB: call()
        CB-->>Ops: CallNotPermittedException
        Note over CB: 서버에 도달하지 않음
    end

    Note over CB: failureRate와 무관하게<br/>모든 요청 즉시 거부
```

### DISABLED: 서킷 비활성화

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버<br/>(DEAD)

    Test->>CB: transitionToDisabledState()
    Note over CB: DISABLED

    loop 5회 실패
        Test->>CB: call()
        CB->>Mock: GET /confirm
        Mock-->>CB: 500 Error
        Note over CB: 에러 발생하지만<br/>상태 전이 없음
    end

    Note over CB: DISABLED 유지<br/>차단 없음
```

### METRICS_ONLY: 메트릭만 수집

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant Mock as Mock서버<br/>(DEAD)

    Test->>CB: transitionToMetricsOnlyState()
    Note over CB: METRICS_ONLY

    loop 5회 실패
        Test->>CB: call()
        CB->>Mock: GET /confirm
        Mock-->>CB: 500 Error
        CB->>CB: 실패 메트릭 기록<br/>(차단하지 않음)
    end

    Note over CB: METRICS_ONLY 유지<br/>failedCalls=5 집계됨<br/>차단 없음
```

---

## TestLogger 이벤트 타이밍 문제

CircuitBreaker의 이벤트 콜백에서 `cb.getMetrics()` 조회 시
현재 호출이 미반영되는 문제와 해결.

### 원인: Resilience4j 내부 실행 순서

```mermaid
sequenceDiagram
    participant Client as 테스트 코드
    participant CB as CircuitBreaker
    participant EP as EventPublisher
    participant State as StateMachine<br/>(메트릭)

    Client->>CB: call() 실행
    CB->>CB: 호출 실행 (성공 or 실패)

    Note over CB: handleThrowable() / handleSuccess()

    CB->>EP: 1. publishEvent()
    activate EP
    EP->>EP: onError/onSuccess 콜백 실행
    Note over EP: 이 시점 getMetrics() →<br/>현재 호출 미반영
    deactivate EP

    CB->>State: 2. stateReference.onError/onSuccess()
    Note over State: 여기서 메트릭 업데이트

    Note over Client: summary() 호출 시점에는<br/>메트릭 반영 완료 → 정확
```

### 해결: AtomicInteger 자체 카운터

```mermaid
sequenceDiagram
    participant CB as CircuitBreaker
    participant EP as EventPublisher
    participant Counter as AtomicInteger<br/>(자체 카운터)

    CB->>EP: publishEvent(onError)
    activate EP
    EP->>Counter: failCount.incrementAndGet()
    Note over Counter: 즉시 반영 → 정확한 값
    Note over EP: cb.getMetrics() 호출 안 함
    deactivate EP

    CB->>CB: stateReference.onError()<br/>(메트릭 업데이트)
```
