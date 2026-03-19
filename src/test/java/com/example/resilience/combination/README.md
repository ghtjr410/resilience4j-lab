# 조합 학습 테스트

3단~5단 조합의 실제 동작과 순서 함정.
개별 2단 조합이 아닌 프로덕션 전체 체인을 검증한다.

---

## FullChainTest

### 3단 조합: 전부 실패 → CB OPEN → 후속 요청 즉시 거절

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant BH as Bulkhead<br/>(maxConcurrent=5)
    participant CB as CircuitBreaker
    participant Retry as Retry<br/>(maxAttempts=3)
    participant Mock as Mock서버<br/>(DEAD)

    rect rgb(255, 230, 230)
        Note over Test: Phase 1: 5건 동시 요청
        Test->>BH: call() × 5
        BH->>CB: 슬롯 확보 → CB 진입
        CB->>Retry: CB 위임

        loop 각 요청당 3회 시도
            Retry->>Mock: → 500
            Retry->>Mock: → 500
            Retry->>Mock: → 500
        end

        Retry-->>CB: 최종 실패 전파
        CB->>CB: 실패 +1 (Retry 재시도는 CB가 모름)
    end

    Note over CB: failedCalls=5<br/>CLOSED → OPEN

    rect rgb(230, 240, 255)
        Note over Test: Phase 2: CB OPEN 후 추가 요청
        Test->>BH: call() × 5
        BH->>CB: 슬롯 확보 → CB 진입
        CB-->>BH: CallNotPermittedException
        Note over CB: Retry 실행 안 됨<br/>서버 도달 안 됨
        BH->>BH: 슬롯 즉시 반환
    end

    Note over BH: availableSlots=5 (전부 가용)
```

### 3단 조합: Retry 성공 → CB 성공 + Bulkhead 정상

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant BH as Bulkhead<br/>(maxConcurrent=5)
    participant CB as CircuitBreaker
    participant Retry as Retry<br/>(maxAttempts=3)
    participant Mock as Mock서버

    Test->>BH: call()
    BH->>CB: 슬롯 확보 → CB 진입
    CB->>Retry: CB 위임

    rect rgb(255, 230, 230)
        Retry->>Mock: 1차 → 500 (DEAD)
        Retry->>Mock: 2차 → 500 (DEAD)
    end

    rect rgb(230, 255, 230)
        Note over Mock: NORMAL 모드 전환
        Retry->>Mock: 3차 → 200 OK
    end

    Retry-->>CB: 최종 성공 전파
    CB->>CB: 성공 +1 (내부 실패 은닉)
    CB-->>BH: 성공 반환
    BH->>BH: 슬롯 반환

    Note over CB: successCalls=1, failedCalls=0
    Note over BH: availableSlots=5 (전부 가용)
```

### 공식 권장 데코레이터 순서

```
Bulkhead(바깥) → CircuitBreaker → Retry(안쪽) → 서버

각 레이어의 역할:
  Bulkhead  : 동시성 제한 — 슬롯 초과 시 즉시 거절
  CB        : 장애 감지 — 실패율 기반 차단
  Retry     : 일시적 장애 복구 — 재시도로 흡수

핵심:
  - Retry 재시도는 CB에 1건으로 집계 (실패율 오염 방지)
  - CB OPEN 시 Retry도 실행 안 됨 (서버 보호)
  - Bulkhead 거절은 CB에 도달하지 않음 (메트릭 오염 방지)
```

---

## FiveLayerChainTest

Resilience4j Spring Boot 기본 Aspect 순서와 동일한 5단 풀체인.

### 데코레이터 순서 (바깥 → 안쪽)

```
Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → 서버
```

### 5단 풀체인: 정상 통과

| 레이어 | 설정 | 역할 |
|--------|------|------|
| Retry | maxAttempts=3 | 최종 실패 시 전체 재시도 |
| CB | failureRate=50% | 실패율 기반 차단 |
| RateLimiter | limit=10/10s | 초당 호출 수 제한 |
| TimeLimiter | timeout=5s | 개별 호출 타임아웃 |
| Bulkhead | maxConcurrent=5 | 동시 실행 수 제한 |

### 기본 Aspect 순서의 함정: RL이 CB 안쪽

```mermaid
sequenceDiagram
    participant Retry as Retry
    participant CB as CircuitBreaker
    participant RL as RateLimiter<br/>(limit=2)

    loop 2회
        Retry->>CB: call()
        CB->>RL: 허용 → 성공
        CB->>CB: 성공 +1
    end

    loop 2회
        Retry->>CB: call()
        CB->>RL: RequestNotPermitted
        CB->>CB: 실패 +1 (RL 거절이 CB 오염!)
    end

    Note over CB: 서버는 정상인데<br/>RL 거절이 CB 실패로 집계
```

### 장애 시 Retry + CB 연동

| Phase | 상태 | 동작 |
|-------|------|------|
| 1 | DEAD → 5건 실패 | 각 Retry 3회 시도 → CB에 5건 집계 → OPEN |
| 2 | CB OPEN | 즉시 거절, 하위 레이어 도달 안 함 |

---

## RetryBulkheadSlotTest

Retry와 Bulkhead 순서에 따른 슬롯 소모 차이.

### 올바른 순서: Bulkhead(바깥) → Retry(안쪽)

```mermaid
sequenceDiagram
    participant BH as Bulkhead<br/>(maxConcurrent=3)
    participant Retry as Retry<br/>(maxAttempts=3)
    participant Mock as Mock서버

    BH->>Retry: 슬롯 1개 확보 → Retry 진입
    Retry->>Mock: 1차 → 실패
    Retry->>Mock: 2차 → 실패
    Retry->>Mock: 3차 → 실패
    Retry-->>BH: 최종 실패 → 슬롯 반환

    Note over BH: 재시도 3회가 같은 슬롯 안에서 실행<br/>슬롯 추가 점유 없음
```

### 잘못된 순서: Retry(바깥) → Bulkhead(안쪽)

```mermaid
sequenceDiagram
    participant Retry as Retry<br/>(바깥)
    participant BH as Bulkhead<br/>(maxConcurrent=2)
    participant Mock as Mock서버

    Retry->>BH: 1차 시도 → 슬롯 확보 → 실패
    Note over BH: 슬롯 반환

    Retry->>BH: 2차 시도 (재시도) → 슬롯 다시 확보
    Note over BH: 다른 요청이 슬롯 점유 중이면?
    BH-->>Retry: BulkheadFullException!

    Note over Retry: 재시도마다 슬롯을 새로 경쟁<br/>→ 동시성 리소스 소모 함정
```

### CB OPEN + CallNotPermittedException 재시도 여부

| retryExceptions 설정 | CB OPEN 시 동작 | 서버 호출 |
|---------------------|-----------------|----------|
| [5xx, timeout] (정상) | 즉시 실패 (재시도 없음) | 0회 |
| [5xx, timeout, **CallNotPermittedException**] (잘못) | 무의미한 재시도 3회 | 0회 (CB가 계속 차단) |

```
절대 CallNotPermittedException을 retryExceptions에 포함하면 안 된다.
CB가 의도적으로 차단한 요청을 Retry가 무력화한다.
```
