# Single Flight 코드 참고

기술 면접 문서의 동작 원리를 확인하기 위한 선택적 예제입니다. 먼저 [Single Flight 문서](../../backend/caching/single-flight.md)를 읽고 필요할 때 소스와 테스트를 참고합니다.

## 실행

JDK 21, Maven 3.9 이상이 필요합니다. Maven Wrapper는 포함하지 않습니다.

```bash
cd examples/cache-stampede
mvn clean verify
mvn spring-boot:run
```

`http://127.0.0.1:8080/products/100?singleFlight=true`로 조회하고 `/stats`에서 누적 모의 원본 조회 횟수를 확인합니다. `singleFlight=false`는 비교 경로입니다. 두 경로가 L1을 공유하므로 서로 다른 신규 Key를 쓰거나 재시작해야 합니다.

## 참고할 코드

- [SingleFlight.java](src/main/java/io/github/waceh/caching/SingleFlight.java): Key별 Future 등록과 leader/follower, 요청별 Future copy
- [ProductService.java](src/main/java/io/github/waceh/caching/ProductService.java): Caffeine L1과 leader의 캐시 재확인
- [SingleFlightTest.java](src/test/java/io/github/waceh/caching/SingleFlightTest.java): 중첩 요청, 실패, timeout/cancel 격리와 객체별 범위
- [기존 검증 기록](docs/verification.md): 2026-10-08 검증에서 테스트 10개와 실행 JAR 확인

## 해석과 한계

통제된 중첩 요청 100개의 모의 원본 조회가 100회에서 1회로 줄어든 결과이며 실제 DB 성능 개선율이 아닙니다. 실제 Redis, DB, 분산 락, Warm-up, Bulkhead, Load Shedding은 구현하지 않았습니다. in-flight Key/follower 수와 Executor의 작업 수를 제한하지 않으므로 운영용으로 바로 도입할 수 없습니다. 요청 timeout이 실제 원본 I/O를 중단시키지는 않습니다.
