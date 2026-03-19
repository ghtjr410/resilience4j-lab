# Timeout 학습 테스트

## 이 디렉토리가 증명하는 것

HTTP 클라이언트 readTimeout 동작.

### TimeoutTest
- TIMEOUT 모드 → readTimeout 발동 → ResourceAccessException
- SLOW(readTimeout 미만) → 느리지만 성공
- SLOW(readTimeout 초과) → 타임아웃 실패

### readTimeout vs TimeLimiter

readTimeout은 네트워크 소켓 레벨, TimeLimiter는 비즈니스 레벨.
TimeLimiter 학습 테스트는 [`timelimiter/`](../timelimiter/README.md) 참조.
