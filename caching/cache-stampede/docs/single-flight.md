# 개념과 Single Flight 구현

## Cache Stampede와 Cache Avalanche

이 저장소에서는 다음처럼 범위와 원인을 구분합니다. 용어는 자료마다 다르며 두 현상은 함께 발생할 수 있습니다.

| 기준 | Cache Stampede | Cache Avalanche |
|---|---|---|
| 관찰 범위 | 동일 Key의 동시 miss와 중복 재생성 | 다수 Key의 miss로 원본 전체에 부하 집중 |
| 대표 원인 | Hot Key 만료, 삭제, eviction, cold start | 동시 만료, 캐시 계층 장애/대량 유실 |
| 직접 대응 | Key별 request coalescing / Single Flight | 원본 부하 예산, Warm-up, TTL jitter, admission control |
| 한계 | 서로 다른 Key의 작업은 합치지 못함 | Hot Key 중복 문제도 함께 제어해야 함 |

100,000개 요청이 같은 상품이면 중복 작업을 합칠 수 있지만, 서로 다른 100,000개 상품이면 Single Flight를 적용해도 원본 조회가 최대 100,000회 필요합니다. TTL jitter는 여러 Key의 동시 만료를 분산하며, 특정 Hot Key 하나의 miss 시점에 몰리는 요청을 직접 합치는 기능은 아닙니다.

Redis 재시작은 반드시 전체 유실을 뜻하지 않습니다. 영속성(RDB/AOF), 복제, failover 방식에 따라 데이터 보존과 손실 범위가 달라집니다. 복구 후 연결 성공과 cache hit ratio 회복은 별개입니다.

## 시간 기반 batching과의 차이

Single Flight는 `Key → 진행 중인 Future`를 관리합니다. 첫 요청은 즉시 작업을 시작하고, 그 작업이 끝나기 전에 들어온 동일 Key 요청은 결과나 실패를 공유합니다. 100ms씩 요청을 모으거나 주기적으로 flush하는 패턴이 아닙니다.

```text
시간       0ms        50ms        150ms           300ms
A          leader: 원본 조회 시작 ---------------- 완료
B                      같은 Future 대기 -------- 결과
C                                  같은 Future - 결과
```

실제 원본이 300ms 걸렸다면 공유 기간도 대략 그 작업 기간입니다. 원본이 10초 걸리면 대기 기간도 길어집니다. 결과 캐시가 없으면 완료 이후 도착한 요청은 새 작업을 시작합니다.

Batching은 시간 창이나 크기 기준으로 여러 요청을 모아 `WHERE id IN (...)` 같은 한 번의 일괄 호출로 처리할 수 있습니다. 서로 다른 Key도 묶을 수 있으며 수집 지연과 결과 분배가 필요합니다. 두 패턴을 함께 적용할 수도 있습니다.

## 이 구현의 원리

1. 빈 `CompletableFuture`를 만들고 `ConcurrentHashMap.putIfAbsent(key, candidate)`로 등록합니다.
2. 등록에 성공한 요청만 leader입니다. 기존 Future를 발견한 요청은 follower가 됩니다.
3. leader는 별도 Executor에서 원본을 조회하고 결과를 L1에 게시합니다. follower는 원본을 실행하지 않습니다.
4. 실제 loader가 종료되면 `remove(key, candidate)`로 해당 항목만 제거하고 공유 Future를 성공/실패로 완료합니다. Executor 제출 거절도 정리합니다.
5. 호출자에게 공유 Future의 `copy()`를 반환해 요청별 timeout/cancel을 격리합니다.

작업 종료와 Future 완료 사이의 매우 짧은 구간에는 새 leader가 선출될 수 있지만, 이전 원본 작업은 이미 끝났습니다. `ProductService`는 leader 내부에서 캐시를 다시 확인하므로 게시된 값을 읽습니다. 캐시가 eviction되거나 loader가 실패했다면 새 원본 작업을 허용합니다. 따라서 보장은 **같은 객체와 Key의 실행 중인 loader 중복 억제**이며, 전체 시간 범위에서 영구히 원본 1회를 보장하는 것이 아닙니다.

I/O를 `computeIfAbsent` 콜백에서 직접 수행하지 않습니다. Future 등록과 loader 제출을 분리해 Map 연산 내부의 블로킹을 피하고 제출 실패를 명시적으로 처리합니다. 작업이 매우 빨리 끝나는 Executor에서도 등록된 항목을 정확히 정리합니다.

## timeout / cancel / 실패

`orTimeout`은 호출 대상 Future 자체를 예외 완료합니다. 공유 Future에 적용하면 한 요청의 deadline이 다른 요청까지 실패시킬 수 있으므로 요청별 복사본에만 적용합니다. `cancel(true)`가 JDBC 조회나 실행 중인 작업을 자동으로 중단시키지는 않습니다.

요청 대기 timeout과 원본 deadline은 별개입니다. 모든 요청이 포기해도 원본이 계속 돌아갈 수 있습니다. 이 예제는 loader 종료 전 in-flight를 강제로 삭제하지 않습니다. 작업 deadline을 넘긴 경우 실제 I/O 중단 여부를 확인하고, 늦은 결과의 캐시 게시 정책을 설계해야 합니다. Future만 실패시키고 새 loader를 허용하면 중복 부하가 다시 발생합니다.

실패는 현재 follower에게 공유되고 캐시에 저장되지 않습니다. 이후 요청은 재시도할 수 있지만 장애가 지속되면 반복 leader 선출로 원본을 압박합니다. 재시도 예산과 Circuit Breaker를 함께 고려해야 합니다.

## 인스턴스 범위와 Key

Spring singleton 서비스가 소유한 Map은 해당 애플리케이션 객체 범위입니다. 같은 JVM에서도 별도 SingleFlight 객체끼리는 공유하지 않습니다. N개 프로세스가 동시에 miss라면 단일 겹친 작업 구간에서 인스턴스당 1회, 총 N회가 발생할 수 있습니다. eviction, 오류 후 재시도, 여러 작업 구간까지 포함하면 총 조회는 N보다 많을 수 있습니다.

Key는 결과가 같다는 계약입니다. 상품 ID만으로 결과가 동등하지 않으면 tenant, 권한 범위, locale, 조회 projection, 데이터 버전 등을 Key에 포함해야 합니다. 서로 다른 사용자 결과를 합치면 데이터 노출과 정합성 문제가 발생합니다.

## Caffeine을 쓰면 직접 구현이 꼭 필요한가?

이 예제는 학습을 위해 `getIfPresent` / `put`과 명시적 Single Flight를 분리합니다. Caffeine의 `cache.get(key, mappingFunction)`은 원자적 계산을 제공하며 `AsyncLoadingCache`는 비동기 로딩 Future를 관리합니다. 같은 캐시 경로만 제어할 목적이라면 라이브러리 기능이 더 단순할 수 있습니다. 캐시 외 ETL/API 중복 작업, 별도 admission control, 여러 계층 로딩 흐름을 조정해야 하는지 보고 결정합니다.

## 공식 참고 자료

- [Go singleflight](https://pkg.go.dev/golang.org/x/sync/singleflight): 같은 Key의 진행 중인 호출과 결과 공유
- [Java 21 CompletableFuture](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html): copy, timeout, cancellation 의미
- [Caffeine Population](https://github.com/ben-manes/caffeine/wiki/Population): 원자적 계산과 비동기 로딩
- [Redis persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/): 복구 시 데이터 보존 특성

본 문서의 Avalanche 구분과 운영 대응 조합은 원인/범위를 설명하기 위한 설계 관점이며, 위 자료들이 이 용어 분류 전체를 규정한다는 의미는 아닙니다.
