# Cache Stampede & Single Flight

동일한 캐시 Key에 동시 miss가 발생할 때 중복 원본 조회를 줄이는 실험입니다. Redis 장애 복구 설계까지 연결하되, **구현한 기능과 운영 설계 제안을 구분**합니다.

## 1. 문제와 실험 범위

인기 상품 `product:100`의 캐시가 만료되면 여러 요청이 동시에 원본을 조회할 수 있습니다. 원본 응답 지연이 길수록 중복 조회가 누적됩니다. Single Flight는 같은 Key의 **진행 중인 작업(in-flight)**을 공유합니다.

| 포함 | 포함하지 않음 |
|---|---|
| Java 21 / Spring Boot 3.5.16 HTTP API | 실제 DB 및 Redis L2 연결 |
| CompletableFuture 기반 Single Flight | 분산 락, Circuit Breaker |
| Caffeine L1, 크기 제한 10,000 / TTL 30초 | Warm-up / Bulkhead / Load Shedding의 실행 구현 |
| 조회 횟수를 세는 모의 원본, 300ms 지연 | 실제 RPS / P95 / P99 부하 벤치마크 |
| 결정적 동시성 테스트, HTTP 통합 테스트 | 운영 환경 성능 또는 장애 복구 보장 |

Spring Boot 버전은 재현을 위해 고정합니다. 최신 버전이라는 의미는 아닙니다. Virtual Thread는 I/O 대기 비용을 줄이지만 원본 처리 용량을 늘리거나 과부하를 막지 않습니다.

## 2. 실행

JDK **21**과 Maven **3.9 이상**이 필요합니다. Maven Wrapper는 포함하지 않습니다.

```bash
cd caching/cache-stampede
java -version
mvn clean verify
mvn spring-boot:run
```

API는 `127.0.0.1:8080`에만 바인딩합니다.

```bash
curl 'http://127.0.0.1:8080/products/100?singleFlight=true'
curl 'http://127.0.0.1:8080/stats'
```

`singleFlight=false`는 Caffeine cache-aside만 사용하는 비교 경로입니다. 두 경로는 같은 L1을 공유하므로, 비교하려면 애플리케이션을 재시작하거나 서로 다른 신규 Key를 사용하세요. 통계는 시작 이후 누적값이며 DB 쿼리 수가 아닌 **모의 원본 조회 횟수**입니다. TTL은 쓰기 후 30초입니다.

간단한 수동 동시 요청 예시(요청 수와 실제 동시성은 다릅니다):

```bash
seq 1 100 | xargs -P 100 -I '{}' curl -s -o /dev/null 'http://127.0.0.1:8080/products/new-baseline?singleFlight=false'
curl 'http://127.0.0.1:8080/stats'
seq 1 100 | xargs -P 100 -I '{}' curl -s -o /dev/null 'http://127.0.0.1:8080/products/new-singleflight?singleFlight=true'
curl 'http://127.0.0.1:8080/stats'
```

HTTP 스케줄링과 L1 hit에 따라 조회 횟수는 달라집니다. 정확한 **중첩 miss 비교**는 아래 자동 테스트를 사용합니다. 성공 상태/오류율까지 측정하려면 별도 부하 도구가 필요합니다.

## 3. 검증 방법

`SingleFlightTest`는 sleep으로 동시성을 추정하지 않습니다. Latch로 원본 작업 완료를 막은 채 요청을 등록하고, 작업을 해제한 뒤 결과를 확인합니다. 요청별 타임아웃 테스트만 실제 타이머를 사용합니다.

| 테스트 | 검증하는 동작 |
|---|---|
| 100개 중첩 miss 비교 | cache-aside 100회, Single Flight 1회, 이후 L1 재사용 |
| 100개 동시 등록 | putIfAbsent의 leader 선출 경쟁에서도 원본 1회 |
| 서로 다른 Key | 별도 원본 작업 2개가 동시에 진행 |
| 원본 실패 | 모든 follower에 같은 원인 전달, 정리 후 재시도 |
| 요청별 timeout / cancel | 다른 follower와 원본 작업에 영향 없음 |
| Executor 제출 거절 | in-flight 제거 및 재시도 가능 |
| 서로 다른 서비스 객체 | 같은 Key라도 각 객체에서 원본 1회씩 |
| 완료 결과 비캐싱 | Single Flight 단독으로는 다음 호출에서 재실행 |
| HTTP 통합 | 실제 임베디드 서버에서 상품 및 통계 응답 |

실행 결과와 한계는 [검증 기록](docs/verification.md)에 기록합니다. 100→1은 통제된 중첩 miss에서의 조회 횟수이며 응답 속도 100배 향상을 의미하지 않습니다.

## 4. 자료 안내

- [개념과 Future 공유 구현](docs/single-flight.md): Stampede / Avalanche, batching과의 차이, Key 설계, timeout과 정리
- [Redis 장애 및 복구 설계](docs/recovery.md): L1/L2, Warm-up, Bulkhead, Load Shedding, 정합성, 분산 락 한계
- [면접 질문과 답변](docs/interview.md): 판단 기준, 꼬리 질문, 답변의 한계

## 5. 소스 구조

```text
src/main/java/io/github/waceh/caching/
├── SingleFlight.java       # Key → 공유 Future, 요청별 copy 반환
├── ProductService.java     # Caffeine cache-aside + leader 재확인
├── LabApplication.java     # Virtual Thread Executor, 모의 300ms 원본
└── ProductController.java  # 요청별 2초 timeout, 누적 조회 통계
```

## 6. 운영 도입 전 한계

이 예제의 Executor, in-flight Key 수, follower 수는 제한하지 않습니다. 원본이 멈추면 요청별 timeout 이후에도 작업과 in-flight 항목이 남을 수 있습니다. 실제 JDBC/HTTP 클라이언트에 원본 deadline과 연결/쿼리 timeout을 설정하고, 작업 수·대기 요청 수·메모리 예산을 별도로 제한해야 합니다. 단순히 Future만 timeout시키고 항목을 삭제하면 아직 실행 중인 원본과 새 작업이 중복될 수 있습니다.

예제는 원본 실패 시 캐시를 채우지 않으며 다음 요청이 재시도합니다. 운영에서는 retry storm을 막는 backoff, jitter, 재시도 예산, 허용 가능한 stale 정책이 필요합니다. HTTP timeout/원본 오류의 별도 오류 매핑도 구현하지 않아 기본 서버 오류 응답이 나올 수 있습니다.

원본 값은 immutable record입니다. 공유하는 값이 mutable이면 follower 간 변경 전파가 생길 수 있습니다. 같은 Key에서 서로 다른 loader를 전달해도 처음 선출된 loader만 실행되므로 호출자가 결과의 동등성을 보장해야 합니다.
