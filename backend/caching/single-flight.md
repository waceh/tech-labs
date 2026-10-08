# Single Flight

## 질문 의도

“Single Flight는 요청을 일정 시간 모으는 것인가요? CompletableFuture는 어떤 역할을 하나요?”

진행 중인 작업 공유와 캐싱/batching을 구분하고, 동시성·실패·deadline·분산 범위를 정확히 설명하는지 평가합니다.

## 핵심 개념

Single Flight는 동일 Key의 **진행 중인 작업(in-flight)**을 공유하는 request coalescing 패턴입니다. 첫 요청이 leader가 되어 작업을 시작하고, 완료 전 도착한 follower는 같은 결과 또는 실패를 기다립니다. 완료 이후 결과 재사용은 별도 캐시의 책임입니다.

| 패턴 | 합치는 대상 | 실행 시점 / 결과 재사용 |
|---|---|---|
| Single Flight | 동일 Key의 겹친 작업 | 첫 요청이 시작, 실행 중 결과 공유 |
| Batching | 같은/다른 Key의 여러 요청 | 시간 또는 크기 기준 수집 후 일괄 처리 |
| Cache | 이미 계산한 결과 | freshness/eviction 정책에 따라 재사용 |

Java에서는 `ConcurrentHashMap<Key, CompletableFuture<Value>>`로 진행 중인 작업을 표현할 수 있습니다. Map은 leader 선출을 원자적으로 수행하고 Future는 결과/예외 전달과 완료 대기를 담당합니다.

```text
A: Key K 등록 성공 → leader가 원본 조회 시작 ─── 완료
B: 같은 K의 Future 발견 → follower 대기 ────── 결과 공유
C: 작업 완료 이후 도착 → 캐시 hit 또는 새 작업
```

100ms 수집 창은 필요하지 않습니다. 공유 기간은 원본 작업의 실제 실행 기간에 따라 달라집니다.

## 면접 답변 예시

> Single Flight는 같은 Key의 진행 중인 작업을 공유합니다. 첫 요청이 Future를 원자적으로 등록하고 원본 조회를 시작하며, 후속 요청은 기존 Future의 결과를 기다립니다. 시간 창으로 요청을 모으는 batching과 다르고, 완료 결과를 보관하는 캐시와도 책임이 다릅니다. 로컬 Map이라면 해당 객체 범위에서만 합쳐지므로 여러 인스턴스에서 전역 한 번을 보장하지 않습니다. 운영에서는 요청별 대기 timeout과 원본 I/O deadline을 분리하고, 공유 Future에 한 요청의 취소가 전파되지 않도록 하겠습니다. 서로 다른 Key의 부하는 별도 원본 동시성 제한으로 제어하겠습니다.

## 실무 적용과 경력 연결

커머스 상품 캐시 miss, 외부 API 조회처럼 같은 Key의 결과가 동등하고 중복 읽기 비용이 큰 경로에 적합합니다. 캐시 확인 후 leader 선출 사이에 다른 작업이 값을 채울 수 있으므로 leader 안에서 캐시를 재확인합니다.

Key는 결과의 동등성 계약입니다. tenant, 권한, locale, projection이 다르면 상품 ID만으로 합쳐서는 안 됩니다. loader가 부작용을 포함하거나 호출자별 다른 결과를 반환하면 적용 조건을 다시 검토합니다.

본인의 운영 경험에 연결할 때 실제 사용한 라이브러리/구현과 검증 범위를 설명합니다. 아래 보조 예제는 학습용이며 개인의 과거 운영 도입 경험을 증명하지 않습니다.

## 예상 꼬리 질문과 답변

**원본이 멈추면요?** follower가 누적되고 in-flight 항목이 남습니다. 요청 대기 deadline 외에 원본 연결/쿼리 timeout, 작업 수·Key 수·대기 요청 수 제한이 필요합니다. Future timeout만으로 JDBC/HTTP I/O가 자동 중단되지는 않습니다.

**한 follower가 취소하면요?** 공유 Future를 직접 timeout/cancel하면 다른 요청에도 영향을 줄 수 있습니다. 요청별 파생 Future나 `copy()`에 대기 timeout을 적용하고 원본 작업 생명주기를 별도로 관리합니다.

**실패하면 다시 실행하나요?** 현재 follower는 실패를 공유합니다. 정리 후 새 요청은 재시도할 수 있으므로 backoff, retry budget, Circuit Breaker 없이는 반복 부하가 생깁니다.

**왜 `remove(key, future)`인가요?** 이전 작업의 정리가 새 작업의 항목을 삭제하지 않도록 identity를 확인합니다. 성공, 실패와 Executor 제출 거절 모두에서 정리가 필요합니다. 종료 전 강제 삭제하면 기존 원본과 새 작업이 겹칠 수 있습니다.

**Caffeine이면 직접 구현해야 하나요?** 같은 캐시의 로딩만 제어한다면 `cache.get(key, mappingFunction)`의 원자적 로딩이나 AsyncLoadingCache를 먼저 검토합니다. 여러 계층/캐시 외 작업을 조정해야 할 때 별도 coordinator의 필요성을 판단합니다.

**Redis 락과 무엇이 다른가요?** 로컬 Future는 결과를 전달합니다. Redis 락은 인스턴스 간 선출 수단이며 결과 전달과 미획득 요청 처리, lease 만료·늦은 쓰기는 별도 설계해야 합니다.

## 한계 / 주의점 및 답변 보완

N개 인스턴스에서 하나의 중첩 miss 구간에 각각 원본을 조회할 수 있습니다. 오류·eviction·여러 실행 구간까지 포함하면 조회 횟수는 N을 넘을 수 있습니다. “DB 조회는 항상 정확히 한 번”이라고 답하지 않습니다.

Single Flight는 cache-aside의 늦은 stale write나 쓰기 정합성을 해결하지 않습니다. mutable 결과 공유도 follower 간 변경 전파를 만들 수 있습니다. 부작용 작업의 exactly-once 보장으로 사용하지 않습니다.

## 선택적 코드 참고 / 공식 자료

- [Java 21 보조 예제](../../examples/cache-stampede/README.md): 소스와 테스트는 선택적으로 확인
- [Go singleflight](https://pkg.go.dev/golang.org/x/sync/singleflight)
- [Java 21 CompletableFuture](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
- [Caffeine Population](https://github.com/ben-manes/caffeine/wiki/Population)
- [다단계 캐시](multi-level-cache.md), [Redis 장애 복구](../resilience/redis-recovery.md)
