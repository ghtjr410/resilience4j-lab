# RateLimiter 학습 테스트

일정 시간 동안 허용 가능한 호출 수를 제한하는 RateLimiter의 핵심 동작.

---

## RateLimiterBasicTest

### 기본 동작: limitForPeriod 초과 → RequestNotPermitted

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant RL as RateLimiter
    participant Mock as Mock서버

    Note over RL: limitForPeriod=3<br/>limitRefreshPeriod=10s<br/>timeoutDuration=0

    loop 3회 성공
        Test->>RL: call()
        RL->>Mock: 허용 → 요청 전달
        Mock-->>RL: 200 OK
    end

    Test->>RL: call() (4번째)
    RL-->>Test: RequestNotPermitted<br/>(허용량 초과, 즉시 거절)
```

### timeoutDuration > 0 → 허용량 갱신까지 대기

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant RL as RateLimiter
    participant Mock as Mock서버

    Note over RL: limitForPeriod=2<br/>limitRefreshPeriod=2s<br/>timeoutDuration=3s

    Test->>RL: call() × 2 → 허용량 소진

    Test->>RL: call() (3번째)
    Note over RL: 허용량 없음 → 대기 시작
    Note over RL: 2초 후 허용량 갱신
    RL->>Mock: 허용 → 요청 전달
    Mock-->>RL: 200 OK

    Note over Test: elapsed >= 2s (갱신 대기)
```

### limitRefreshPeriod 경과 후 허용량 갱신

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant RL as RateLimiter

    Note over RL: limitForPeriod=2<br/>limitRefreshPeriod=2s

    rect rgb(230, 240, 255)
        Note over RL: 첫 주기
        Test->>RL: call() × 2 → 성공
        Test->>RL: call() → RequestNotPermitted
    end

    Note over Test: 2.5초 대기 (갱신)

    rect rgb(230, 255, 230)
        Note over RL: 두 번째 주기 (허용량 리셋)
        Test->>RL: call() × 2 → 성공
    end
```

### 동시 요청에서 정확히 limitForPeriod만 통과

| 설정 | 동시 요청 | 통과 | 거절 |
|------|----------|------|------|
| limitForPeriod=5 | 10건 | 5건 | 5건 |

### RateLimiter(바깥) + CB(안쪽) → 올바른 순서

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant RL as RateLimiter<br/>(바깥)
    participant CB as CircuitBreaker<br/>(안쪽)
    participant Mock as Mock서버

    Test->>RL: call() × 2 → 허용 → CB → Mock → 성공
    Test->>RL: call() × 2 → RequestNotPermitted
    Note over RL: CB에 도달하지 않음

    Note over CB: successCalls=2, failedCalls=0
    Note over CB: RL 거절은 CB 메트릭에 영향 없음
```

### CB(바깥) → RateLimiter(안쪽) — 잘못된 순서

```mermaid
sequenceDiagram
    participant Test as 테스트
    participant CB as CircuitBreaker<br/>(바깥)
    participant RL as RateLimiter<br/>(안쪽)

    Test->>CB: call() × 2 → CB → RL → 성공
    Test->>CB: call() × 2 → CB → RL → RequestNotPermitted
    CB->>CB: 실패 +2 (RL 거절이 CB에 집계!)

    Note over CB: 서버는 정상인데<br/>rate limit 초과로 CB 실패 집계
```

### 핵심 원칙

```
RateLimiter(바깥) → CircuitBreaker(안쪽) → 서버
     ↑ 호출 빈도 제한이 먼저
     ↑ 거절된 요청은 CB에 도달하지 않음
     ↑ CB 실패율이 오염되지 않음
```
