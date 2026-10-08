# Redis 장애 및 복구 설계

이 문서는 운영 적용을 위한 설계 제안입니다. 실습 코드에는 Caffeine L1만 연결되어 있고 Redis, 실제 DB, 아래 보호 장치는 구현하지 않았습니다.

## L1 / L2와 요청 흐름

| 계층 | 역할 | 위험과 한계 |
|---|---|---|
| Caffeine L1 | 인스턴스 내부에서 네트워크 없이 hot data 재사용 | 메모리/크기 제한, 각 인스턴스의 값이 다름, 재시작 시 cold |
| Redis L2 | 여러 인스턴스가 공유하는 캐시 | 네트워크 지연, 장애/eviction, L1 자동 무효화 없음 |
| 원본 DB/API | 데이터의 기준 및 miss 시 재생성 | 처리 용량 제한, fallback 폭증 가능 |

권장 흐름의 한 예:

```text
요청 → L1 확인
       miss → 인스턴스별 Single Flight
               leader → L1 재확인 → L2 조회(Circuit Breaker / 짧은 timeout)
                          L2 hit → L1 게시 → 결과 공유
                          miss/우회 → 원본 Bulkhead admission
                                      허용 → 원본 조회 → L2/L1 게시 → 결과 공유
                                      거절 → 허용된 stale / 기능 축소 / 빠른 오류
               follower → 자기 deadline 안에서 공유 결과 대기
```

Single Flight를 L2 앞에 배치하면 같은 Key의 Redis 조회까지 합칠 수 있습니다. L2 miss 이후에 배치하면 Redis 조회는 각 요청이 수행합니다. 정답은 하나가 아니며 L1 hit ratio, L2 비용, 결과 정합성에 따라 선택합니다. leader 재확인은 캐시 확인과 선출 사이의 경쟁을 줄입니다.

L2 쓰기 실패 시 조회 성공을 응답할지 여부는 정책입니다. 일반 상품 설명은 원본 결과와 L1을 활용할 수 있지만, L2 장애를 삼키면서 무제한 DB fallback을 허용해서는 안 됩니다. L2 write timeout, 실패 지표, 재시도 예산을 별도로 둡니다.

## 장애 중 원본 보호

1. Redis 장애를 **실제 miss와 별도 지표로 구분**합니다. 짧은 연결/명령 timeout, Circuit Breaker로 반복 대기를 줄이고 half-open probe 수를 제한합니다.
2. 사용 가능한 L1을 유지합니다. 장애라고 모든 L1을 비우면 잔존 보호막까지 없애는 결과가 됩니다. TTL 경과 후 stale을 허용하려면 별도 보존 구조와 최대 stale age가 필요합니다. 이 예제의 Caffeine은 만료된 값을 반환하지 않습니다.
3. Hot Key는 Single Flight로 합치되, 서로 다른 Key는 원본 Bulkhead로 제한합니다. follower는 원본 permit을 점유하지 않고 leader만 원본 호출 직전에 획득해야 합니다.
4. 원본 동시성 초과는 빠르게 거절합니다. 무한 대기 큐는 timeout과 메모리 고갈을 늦춰 발생시킬 뿐입니다. 사용자 대기 요청 수, Key 수, 연결 풀 대기에도 한도를 둡니다.

Bulkhead는 처리 자원을 분리/제한하는 방식이고, Load Shedding은 감당할 수 없는 작업을 버리는 정책입니다. `Semaphore.tryAcquire()` 실패 시 즉시 503 또는 허용된 fallback을 반환하는 방식은 둘을 함께 적용한 예입니다. 사용자별 rate limit 위반은 429가 적합할 수 있습니다. 재시도를 유도한다면 backoff와 jitter를 안내하고 무한 재시도를 피합니다.

인스턴스당 20개 permit을 두더라도 10개 인스턴스는 최대 200개 원본 작업을 실행할 수 있습니다. autoscaling이 DB 한도를 자동으로 보장하지 않습니다. 전역 DB 예산에서 foreground, Warm-up, batch, 연결 풀 여유를 배분하고 인스턴스 수 변화까지 반영해야 합니다. 처리율과 동시성은 달라, 느려진 쿼리는 같은 유입량에서도 더 많은 permit을 오래 점유합니다.

## 복구와 Warm-up

Redis 연결이 살아났다고 트래픽 제한을 즉시 해제하지 않습니다. 유실 여부, 유효 데이터 비율, hot set의 hit ratio를 확인합니다.

- **우선순위:** 최근 접근 빈도와 비즈니스 중요도가 높은 Key부터 적재합니다. 전체 데이터 일괄 스캔은 피하고 실제 hot set을 사용합니다.
- **예산:** foreground 원본 부하를 먼저 보호합니다. Warm-up 전용 낮은 동시성과 rate limit을 두되 전체 원본 예산을 넘지 않게 합산합니다. DB latency/오류/풀 대기가 증가하면 적재를 줄이거나 중단합니다.
- **중복 제어:** 같은 프로세스의 foreground와 Warm-up이 같은 coordinator를 공유하도록 설계합니다. 여러 인스턴스가 전체 hot set을 각각 적재하지 않게 단일 작업자, partition 또는 lease 등으로 분담합니다.
- **점진 회복:** 제한된 트래픽부터 확대합니다. Redis hit ratio만 보지 말고 DB latency, active queries, pool pending, API P95/P99, 오류율, 대기 요청 수, 원본 QPS를 함께 확인합니다.
- **재발 방지:** TTL jitter, 필요하면 early refresh / stale-while-revalidate를 적용합니다. 갱신 작업도 coalescing과 부하 예산을 사용해야 합니다.

목표 hit ratio나 semaphore 수를 임의의 고정 수치로 정답처럼 제시하지 않습니다. 실제 원본 용량, 지연 분포, 장애 시 SLO와 함께 부하 실험으로 정합니다.

## 정합성과 허용 가능한 stale

L1/L2는 같은 데이터를 서로 다른 시점에 보유할 수 있습니다. L1 TTL은 L2의 남은 TTL이나 원본 freshness 제한을 무시해 무조건 연장하면 안 됩니다. version 또는 원본 갱신 시각을 저장하고 전체 허용 지연을 제한합니다. TTL을 짧게 하는 것만으로 강한 정합성을 보장하지는 않습니다.

대표적인 cache-aside 경쟁:

```text
Reader: DB에서 v1 조회 시작
Writer: DB를 v2로 갱신하고 캐시 무효화
Reader: 늦게 도착한 v1을 캐시에 다시 게시
```

Single Flight와 분산 락만으로 이 경쟁을 해결하지 못합니다. version 비교와 원자적 조건부 게시, 캐시 세대 번호, 이벤트 기반 무효화, 쓰기 경로 설계를 검토합니다. 버전 정보를 삭제로 함께 잃으면 늦은 v1을 비교할 기준도 사라질 수 있어 tombstone/세대 정보를 보존하는 설계가 필요합니다. Redis Lua/CAS는 Redis 내 비교를 원자적으로 할 수 있지만 DB와 Redis 간 트랜잭션을 자동으로 만들어 주지는 않습니다.

Kafka/RabbitMQ 무효화 이벤트도 지연·중복·순서 역전·유실을 고려합니다. DB commit과 이벤트 발행의 간극에는 transactional outbox 등 신뢰성 설계가 필요하며, version 기반 멱등 처리와 주기적 보정/TTL을 함께 검토합니다. L2 key를 지워도 모든 L1이 즉시 무효화되는 것은 아닙니다.

상품 설명/추천은 비즈니스가 허용한 최대 stale age 내에서 저하 응답이 가능할 수 있습니다. 재고·결제·권한·개인정보는 같은 stale 정책을 일괄 적용하지 않습니다. 실제 사용자의 Redis 장애 경험에 적용할 때도 데이터별 허용 정합성을 먼저 확인해야 합니다.

## 분산 락의 범위와 한계

Redis 기반 Key별 락은 인스턴스 간 재생성을 줄이는 수단입니다. 락은 결과를 전달하지 않으므로 미획득 요청은 L2 재확인, 제한된 polling, stale 응답 또는 거절 정책이 필요합니다.

- 단일 Redis에서 `SET lock-key unique-token NX PX lease` 후 소유 token이 일치할 때만 원자적으로 삭제합니다. 만료 후 새 소유자가 생겼는데 이전 소유자가 무조건 `DEL`하면 새 락을 지울 수 있습니다.
- lease보다 긴 원본 지연, GC pause, 네트워크 분리로 락이 만료되면 두 작업이 동시에 실행될 수 있습니다. token 기반 해제만으로 늦은 쓰기를 막지는 못합니다.
- fencing token은 보호 대상이 단조 증가 token을 검증하고 오래된 작업을 거절할 때 의미가 있습니다. UUID 소유 token과 fencing token은 다른 개념입니다.
- 단일 노드 비동기 복제와 failover에는 락 안전성 문제가 생길 수 있습니다. Redlock 등도 장애 모델과 시간 가정, 보호 대상의 검증이 필요하며 모든 환경의 exactly-once를 보장하지 않습니다.
- Redis 자체가 불가능하면 Redis 락도 사용할 수 없습니다. 다른 coordination 계층을 검토하더라도 자체 장애/지연 비용이 생깁니다. 로컬 Single Flight, 원본 예산, Load Shedding은 여전히 필요합니다.
- 분산 락은 서로 다른 Key의 대량 miss를 합치지 못하고 DB 정합성/트랜잭션을 대신하지 않습니다.

캐시 재생성은 중복 읽기를 일부 허용하면서 원본을 보호하는 설계가 더 단순할 수 있습니다. 결제 같은 부작용 작업이라면 캐시 락을 가져다 쓰지 말고 원본의 멱등성/고유 제약/트랜잭션으로 별도 설계해야 합니다.

## 관측과 다음 실험

L1/L2 hit/miss, Redis timeout/오류, leader/follower 수, in-flight Key 수, follower 대기 수, 원본 QPS·동시성, admission 거절, Warm-up 속도, stale 응답, API 지연과 오류를 수집합니다. 고유 상품 ID를 metric label로 넣으면 cardinality가 폭증하므로 집계하거나 제한적으로 샘플링합니다.

실제 Redis/PostgreSQL 연결 후 (1) hot Key 만료, (2) 다양한 Key 동시 miss, (3) Redis 연결 장애, (4) empty-cache 복구, (5) 느린 원본, (6) 다중 인스턴스, (7) 갱신/무효화 경쟁을 각각 재현해야 합니다. 이 저장소의 테스트 결과가 이 시나리오들의 검증을 대신하지 않습니다.

## 공식 참고 자료

- [Redis distributed locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/): 소유 token, lease와 안전성 가정
- [Redis persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/): RDB/AOF와 데이터 보존
- [Caffeine Population](https://github.com/ben-manes/caffeine/wiki/Population): 로컬 캐시 로딩 방식

운영 예산, 배치 순서, 정합성 정책은 서비스 특성에 따른 설계 제안입니다.
