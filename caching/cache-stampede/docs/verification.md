# 검증 기록

## 실제 실행 결과

- 검증일: 2026-10-08 (Asia/Seoul)
- 환경: macOS arm64, Eclipse Temurin JDK 21.0.12.1, Maven 3.9.9
- 의존성: Spring Boot 3.5.16, Caffeine(버전은 Boot BOM 관리)
- 명령: `mvn clean verify`
- 결과: **BUILD SUCCESS**, 테스트 **10개**, 실패/오류/skip **0개**
- Spring Boot 실행 JAR 패키징 성공
- 패키징한 JAR 직접 기동(18081 포트): 동일 상품 2회 HTTP 200, 누적 `originReads=1` 확인 후 종료

조회 횟수 비교 테스트의 실제 출력:

```text
singleFlight=false requests=100 originReads=100
singleFlight=true requests=100 originReads=1
```

테스트는 원본 완료를 Latch로 차단해 100개 miss가 겹치도록 만든 후 해제합니다. 같은 Key의 동시 등록 경쟁, 다른 Key의 독립 실행, 원본 실패 공유/재시도, 요청별 timeout/cancel 격리, Executor 거절 정리, 객체별 범위, 완료 결과 비캐싱도 통과했습니다.

HTTP 통합 테스트는 RANDOM_PORT로 실제 임베디드 서버를 띄워 `/products/http-smoke`의 200 응답과 상품 ID, `/stats`의 조회 통계를 확인했습니다.

## 해석의 한계

원본은 실제 DB 대신 계수기와 지연/동기화 hook입니다. 비교 테스트의 100→1은 중첩 miss의 중복 원본 호출 억제 결과입니다. 실제 DB 부하 감소율, RPS, P95/P99 또는 전체 응답 시간의 개선 배수로 해석하지 않습니다.

Redis L2, 다중 프로세스, Redis 장애 및 복구, 분산 락, Warm-up, Bulkhead, Load Shedding은 구현하거나 실측하지 않았습니다. 서로 다른 서비스 객체 테스트는 coordinator의 로컬 범위를 검증하며 실제 네트워크 분산 환경을 검증하지 않습니다.

일반 HTTP 호출은 스케줄링과 캐시 hit 때문에 테스트와 다른 조회 횟수가 나올 수 있습니다. 운영 검증 시 부하 도구, 데이터셋/Key 분포, 원본 용량, 인스턴스 수, timeout과 캐시 정책을 함께 기록해야 합니다.
