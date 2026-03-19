# Bulkhead 학습 테스트

동시 호출 제한과 장애 격리. Bulkhead는 동시 요청 수를 물리적으로 제한하여
하나의 장애가 전체 시스템으로 번지는 것을 막는다.

---

## BulkheadBasicTest

### 동시 25건 중 20건 통과, 5건 즉시 거절

```mermaid
sequenceDiagram
    participant T1 as Thread 1~20
    participant T2 as Thread 21~25
    participant BH as Bulkhead<br/>(maxConcurrent=20)
    participant Mock as Mock서버<br/>(SLOW 3초)

    Note over BH: maxConcurrentCalls=20<br/>maxWaitDuration=0

    par 25개 스레드 동시 시작
        T1->>BH: call() × 20
        BH->>Mock: 슬롯 확보 → 요청 전달
        Note over Mock: 3초 대기 중...

        T2->>BH: call() × 5
        BH-->>T2: BulkheadFullException<br/>(슬롯 없음, 즉시 거절)
    end

    Note over T2: 거절된 요청은<br/>500ms 이내 완료 (fail-fast)

    Mock-->>BH: 200 OK × 20 (3초 후)
```

### maxWaitDuration > 0 → 대기 후 통과

```mermaid
sequenceDiagram
    participant T1 as Thread 1~3<br/>(1차)
    participant T2 as Thread 4~6<br/>(대기)
    participant BH as Bulkhead<br/>(maxConcurrent=3)
    participant Mock as Mock서버<br/>(SLOW 2초)

    Note over BH: maxConcurrentCalls=3<br/>maxWaitDuration=5s

    par 6개 스레드 동시 시작
        T1->>BH: call() × 3
        BH->>Mock: 슬롯 확보 → 요청 전달

        T2->>BH: call() × 3
        Note over T2: 슬롯 없음 → 대기열 진입<br/>(최대 5초 대기)
    end

    Note over Mock: 2초 후 응답
    Mock-->>BH: 200 OK × 3

    Note over BH: 슬롯 반환 → 대기중 스레드 진입
    BH->>Mock: 대기했던 3건 요청 전달
    Mock-->>BH: 200 OK × 3

    Note over BH: 전체 6건 성공<br/>거절 0건
```

### Bulkhead(바깥) + CB(안쪽) → 거절이 CB 실패로 집계 안 됨

```mermaid
sequenceDiagram
    participant T1 as Thread 1~2
    participant T2 as Thread 3~4
    participant BH as Bulkhead<br/>(maxConcurrent=2)
    participant CB as CircuitBreaker
    participant Mock as Mock서버<br/>(SLOW 3초)

    par 4개 스레드 동시 시작
        T1->>BH: call() × 2
        BH->>CB: 슬롯 확보 → CB 진입
        CB->>Mock: 요청 전달

        T2->>BH: call() × 2
        BH-->>T2: BulkheadFullException
        Note over T2: CB에 도달하지 않음
    end

    Mock-->>CB: 200 OK × 2
    CB->>CB: 성공 기록

    Note over CB: failedCalls=0, state=CLOSED
    Note over CB: Bulkhead 거절은<br/>CB 메트릭에 영향 없음
```

### 슬롯 반환 → 대기 중이던 요청 통과

```mermaid
sequenceDiagram
    participant T1 as Thread 1~2<br/>(슬롯 점유)
    participant T3 as Thread 3<br/>(대기)
    participant BH as Bulkhead<br/>(maxConcurrent=2)
    participant Mock as Mock서버<br/>(SLOW 2초)

    Note over BH: maxWaitDuration=5s

    T1->>BH: call() × 2
    BH->>Mock: 슬롯 확보 → 요청 전달
    Note over BH: 가용 슬롯: 0

    Note over T3: 1초 후 요청
    T3->>BH: call()
    Note over BH: 슬롯 없음 → 대기열 진입

    Note over Mock: 2초 후 응답
    Mock-->>BH: 200 OK × 2
    Note over BH: 슬롯 반환 → 가용 슬롯: 2

    BH->>Mock: 대기 중이던 요청 전달
    Note over T3: 슬롯 대기 ~1초 + SLOW 2초
    Mock-->>BH: 200 OK

    Note over BH: 전체 3건 성공
```

### CB(바깥) → Bulkhead(안쪽) — 잘못된 순서

```mermaid
sequenceDiagram
    participant T1 as Thread 1~2
    participant T2 as Thread 3~4
    participant CB as CircuitBreaker<br/>(바깥)
    participant BH as Bulkhead<br/>(안쪽, maxConcurrent=2)
    participant Mock as Mock서버<br/>(SLOW 3초)

    par 4개 스레드 동시 시작
        T1->>CB: call() × 2
        CB->>BH: CB 진입 → Bulkhead 확인
        BH->>Mock: 슬롯 확보 → 요청 전달

        T2->>CB: call() × 2
        CB->>BH: CB 진입 → Bulkhead 확인
        BH-->>CB: BulkheadFullException
        CB->>CB: 실패 +2
        Note over CB: 서버는 정상인데<br/>Bulkhead 거절이 실패로 집계!
    end

    Note over CB: failedCalls=2<br/>서킷이 의도치 않게 열릴 수 있음
```

### CB OPEN 시 Bulkhead 슬롯 소비 → 즉시 반환

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant BH as Bulkhead<br/>(maxConcurrent=3)
    participant CB as CircuitBreaker<br/>(FORCED_OPEN)

    Note over BH: availableSlots=3

    loop 10회 호출
        Test->>BH: call()
        Note over BH: 슬롯 확보 (3→2)
        BH->>CB: CB 진입
        CB-->>BH: CallNotPermittedException<br/>(동기적, 마이크로초)
        Note over BH: 슬롯 즉시 반환 (2→3)
    end

    Note over BH: availableSlots=3 (변함없음)
    Note over BH: CB 거절이 동기적이므로<br/>슬롯 점유 시간 ≈ 0<br/>실질적 영향 없음
```

---

## ThreadPoolBulkheadTest

SemaphoreBulkhead와 다른 실행 모델 — 별도 스레드풀 + 큐.

### SemaphoreBulkhead vs ThreadPoolBulkhead

| 항목 | SemaphoreBulkhead | ThreadPoolBulkhead |
|------|-------------------|---------------------|
| 실행 스레드 | 호출자 스레드 그대로 | 전용 스레드풀 |
| 제한 방식 | maxConcurrentCalls | maxThreadPoolSize + queueCapacity |
| 반환 타입 | T (동기) | CompletionStage\<T\> (비동기) |
| ThreadLocal | 유지됨 | 유실됨 (contextPropagator 필요) |

### maxThreadPoolSize + queueCapacity 초과 → 거절

```mermaid
sequenceDiagram
    participant T1 as 요청 1~2
    participant T2 as 요청 3~4
    participant T3 as 요청 5~6
    participant TP as ThreadPoolBulkhead<br/>(thread=2, queue=2)
    participant Mock as Mock서버<br/>(SLOW 3초)

    T1->>TP: submit() × 2
    Note over TP: 스레드풀에서 실행 (2/2)
    TP->>Mock: 요청 전달

    T2->>TP: submit() × 2
    Note over TP: 큐에서 대기 (2/2)

    T3->>TP: submit() × 2
    TP-->>T3: BulkheadFullException<br/>(스레드 + 큐 모두 가득)
```

### 큐 대기 → 스레드 반환 → 자동 실행

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant TP as ThreadPoolBulkhead<br/>(thread=2, queue=2)
    participant Mock as Mock서버<br/>(SLOW 2초)

    Test->>TP: submit() × 4
    Note over TP: 2건 실행 + 2건 큐 대기

    Note over Mock: 2초 후 응답
    Mock-->>TP: 200 OK × 2
    Note over TP: 스레드 반환 → 큐의 2건 자동 실행

    Note over Mock: 2초 후 응답
    Mock-->>TP: 200 OK × 2

    Note over TP: 전체 4건 성공
```

### ThreadPoolBulkhead(바깥) + CB(안쪽)

| 설정 | 동시 요청 | 통과 | 거절 | CB 실패 |
|------|----------|------|------|---------|
| thread=2, queue=0 | 4건 | 2건 | 2건 | 0건 |

거절은 CB 도달 전에 발생 → CB 메트릭 영향 없음.

---

### 핵심 원칙

```
Bulkhead(바깥) → CircuitBreaker(안쪽) → 서버
     ↑ 동시성 제한이 먼저
     ↑ 거절된 요청은 CB에 도달하지 않음
     ↑ CB 실패율이 오염되지 않음
     ↑ CB OPEN 시 슬롯은 잡았다가 즉시 반환 (무해)

CB(바깥) → Bulkhead(안쪽) → 서버  ← 잘못된 순서!
     ↑ BulkheadFullException이 CB 실패로 집계
     ↑ 서버 정상인데 서킷 열림 (오작동)
```
