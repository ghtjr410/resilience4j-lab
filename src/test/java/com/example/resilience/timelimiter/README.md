# TimeLimiter 학습 테스트

CompletableFuture 기반 비동기 호출의 타임아웃 제어.
RestTemplate의 readTimeout과는 다른 레이어에서 동작한다.

---

## TimeLimiterBasicTest

### 기본 동작: timeoutDuration 초과 → TimeoutException

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant TL as TimeLimiter
    participant Future as CompletableFuture
    participant Mock as Mock서버<br/>(SLOW 5초)

    Note over TL: timeoutDuration=2s

    Test->>TL: call()
    TL->>Future: supplyAsync → Mock 호출
    Note over Mock: 5초 대기 중...

    Note over TL: 2초 경과 → 타임아웃
    TL-->>Test: TimeoutException
```

### cancelRunningFuture=true → Future 취소

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant TL as TimeLimiter
    participant Future as CompletableFuture
    participant Mock as Mock서버<br/>(SLOW 5초)

    Note over TL: timeoutDuration=1s<br/>cancelRunningFuture=true

    Test->>TL: call()
    TL->>Future: supplyAsync → Mock 호출
    Note over TL: 1초 후 타임아웃
    TL->>Future: cancel(true)
    Note over Future: isCancelled=true<br/>(리소스 해제)
    TL-->>Test: TimeoutException
```

### cancelRunningFuture=false → 작업 계속 실행

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant TL as TimeLimiter
    participant Future as CompletableFuture
    participant Mock as Mock서버<br/>(SLOW 3초)

    Note over TL: timeoutDuration=1s<br/>cancelRunningFuture=false

    Test->>TL: call()
    TL->>Future: supplyAsync → Mock 호출
    Note over TL: 1초 후 타임아웃
    TL-->>Test: TimeoutException<br/>(호출자에게 즉시 반환)

    Note over Future: 취소되지 않음!<br/>백그라운드에서 계속 실행
    Note over Mock: 3초 후 응답
    Mock-->>Future: 200 OK
    Note over Future: isDone=true (정상 완료)
```

### TimeLimiter + CB 조합

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker
    participant TL as TimeLimiter
    participant Mock as Mock서버<br/>(SLOW 5초)

    Note over TL: timeoutDuration=1s

    loop 3회
        Test->>CB: call()
        CB->>TL: 위임
        TL-->>CB: TimeoutException (1초 후)
        CB->>CB: 실패 +1
    end

    Note over CB: failedCalls=3 → OPEN
```

### readTimeout vs TimeLimiter

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant TL as TimeLimiter
    participant RT as RestTemplate
    participant Mock as Mock서버<br/>(SLOW 3초)

    rect rgb(255, 230, 230)
        Note over Test: Case 1: readTimeout(2s) < TimeLimiter(5s)
        Test->>TL: call()
        TL->>RT: supplyAsync
        RT->>Mock: 요청
        Note over RT: 2초 후 readTimeout 발동
        RT-->>TL: ResourceAccessException
        Note over TL: Future 완료 (예외)
        TL-->>Test: ResourceAccessException<br/>(TimeoutException 아님!)
    end

    rect rgb(230, 240, 255)
        Note over Test: Case 2: TimeLimiter(2s) < readTimeout(10s)
        Test->>TL: call()
        TL->>RT: supplyAsync
        RT->>Mock: 요청
        Note over TL: 2초 후 TimeLimiter 발동
        TL-->>Test: TimeoutException<br/>(ResourceAccessException 아님!)
        Note over RT: 아직 Mock 응답 대기 중
    end

    Note over Test: 둘은 독립적으로 동작<br/>더 짧은 쪽이 먼저 발동<br/>예외 타입이 다름!
```
