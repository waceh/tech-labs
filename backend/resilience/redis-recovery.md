# Redis 장애 복구와 Graceful Degradation

## 질문 의도

“Redis가 장애 난 동안 서비스는 어떻게 유지하고, 복구 후에는 어떻게 정상화하겠습니까?”

장애 의존성을 우회하면서 원본을 보호하고, 데이터별 기능 축소와 점진 회복을 설계하는지 평가합니다. 연결 복구와 사용자 관점 회복을 구분하는 것이 중요합니다.

## 핵심 개념

Graceful Degradation은 핵심 기능과 자원 예산을 보호하며 일부 기능, 품질 또는 freshness를 제한하는 전략입니다. 모든 요청을 무조건 성공시키는 뜻은 아닙니다. 이 문서는 Redis를 **재생성 가능한 캐시**로 사용하는 상황을 전제로 합니다. 세션, 락, 원본 데이터 역할까지 맡는다면 별도의 영향 분석이 필요합니다.

| 수단 | 목적 | 단독 적용의 한계 |
|---|---|---|
| 짧은 timeout / [Circuit Breaker](circuit-breaker.md) | Redis 반복 대기·호출을 줄임 | DB fallback 부하를 제한하지 않음 |
| [L1](../caching/multi-level-cache.md) / 제한된 stale | 원본 접근을 줄이고 일부 응답 유지 | freshness·데이터별 허용 기준 필요 |
| [Single Flight](../caching/single-flight.md) | 동일 Key 중복 원본 조회 억제 | 다른 Key / 다른 인스턴스는 별도 |
| Bulkhead | 원본 작업 자원을 분리·제한 | 무한 대기 큐를 허용하면 대기 부하 누적 |
| Load Shedding | 감당하지 못하는 작업을 빠르게 거절 | 거절 대상과 사용자 응답 정책 필요 |
| Warm-up | 복구 후 중요한 캐시부터 채움 | foreground와 원본 용량 경쟁 |

### 역할에 따른 SPOF 판단

Redis가 한 대라는 사실만으로 서비스 전체의 [SPOF](spof-high-availability.md) 여부가 결정되지는 않습니다. 재생성 가능한 캐시라면 원본 fallback으로 핵심 기능을 유지할 수 있지만, 대체 용량과 timeout·원본 보호가 검증되어야 합니다. 세션의 유일한 저장소라면 같은 장애가 로그인 유지나 인증 기능 중단으로 이어질 수 있으므로 해당 기능의 대체 경로를 별도로 평가합니다.

### Replication과 Failover

Replication은 데이터를 복제본에 전달하는 것이고 Failover는 장애 시 다른 노드가 Primary 역할을 이어받도록 전환하는 것입니다.

```text
Primary ── 비동기 복제 ──> Replica
Primary 장애 → 감지/선출 → Replica 승격 → Client가 새 Primary로 재연결
```

Redis의 기본 복제는 비동기이므로 승격 시 복제되지 않은 최신 쓰기가 유실될 수 있습니다. Replica가 존재한다는 사실만으로 자동 승격이나 Client 전환이 보장되지 않습니다. [Redis 복제 문서](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/)

- Sentinel은 비Cluster 구성의 감지·선출·Failover를 담당하며 Sentinel 자체의 장애 격리와 선출에 필요한 수, 지원 Client도 확인해야 합니다. [Redis Sentinel](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/)
- Redis Cluster는 Primary 간 샤딩과 Replica 복제를 함께 구성할 수 있으며 승격 조건과 Cluster 지원 Client가 필요합니다. [Redis Cluster](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/)
- ElastiCache의 노드 기반 Valkey/Redis OSS는 지원 조건에 맞는 Replica·Multi-AZ·자동 Failover 설정을 확인합니다. 이 기능을 일반 노드 기반 Memcached에 적용한다고 설명하지 않습니다. [AWS Multi-AZ](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/AutoFailover.html)

가용성 회복과 무손실은 다른 목표입니다. 허용 데이터 손실, 이전 Primary의 쓰기, Client 재연결·재시도와 실제 복구 시간을 함께 검증합니다. Replica는 잘못된 삭제도 복제할 수 있으므로 백업을 대체하지 않습니다.

## 면접 답변 예시

> 재생성 가능한 캐시의 장애에서는 정상 miss와 접근 실패를 구분하고 timeout과 Circuit Breaker로 반복 대기를 줄입니다. 유효한 로컬 캐시를 활용하고 동일 Key 조회는 병합하되, 다른 Key의 원본 fallback은 동시성 한도로 제어해야 합니다. stale 응답이나 기능 축소는 업무상 허용된 범위에서 적용하며 재고·결제·권한은 별도 검증하거나 명시적으로 실패해야 합니다. 복구 후에는 데이터 보존 범위와 hot set의 적중률을 확인하고 Warm-up과 사용자 요청을 전체 원본 예산 안에서 처리합니다. 원본 지연, 풀 대기, API tail latency와 오류율을 확인하며 트래픽 제한을 점진적으로 해제합니다.

## 실무 적용과 설계 판단 기준

### 장애 감지와 운영 알림

Redis GET 성공 후 값이 없는 정상 miss와 timeout·연결 오류를 구분합니다. 성공한 호출도 느려질 수 있으므로 실패율뿐 아니라 호출 지연과 slow call 비율을 관측합니다. 서킷 발동은 최소 표본과 관측 구간을 기준으로 판단하고, OPEN에서는 Redis를 반복 호출하지 않은 채 아래 원본 보호와 fallback 정책을 적용합니다.

서킷 상태·차단 호출·fallback 비율과 API/DB 영향 지표를 수집하고, Prometheus → Alertmanager → Slack으로 지속 장애와 사용자 영향을 알립니다. 상태 전환 로그를 함께 남기고 서비스·환경·의존성별로 알림을 묶습니다. HALF_OPEN 전환이나 알림 resolved만으로 전체 서비스 복구를 선언하지 않습니다. 감지 시나리오, 요청 흐름과 설정 예시는 [Circuit Breaker 문서](circuit-breaker.md)를 참고합니다.

### 장애 중

1. Redis timeout/연결 오류와 정상 miss를 분리해 영향 범위를 확인합니다. cache 외 Redis 사용처도 점검합니다.
2. 유효한 L1을 유지합니다. 장애를 이유로 전체 L1을 비우면 원본 부하가 커집니다. stale은 별도 보존과 최대 age 정책이 있을 때만 사용합니다.
3. leader만 원본 호출 직전에 permit을 획득합니다. follower도 별도의 대기 수/메모리 한도가 필요합니다.
4. 원본 예산 초과 시 빠르게 기능을 축소하거나 오류를 반환합니다. 서비스 과부하에는 503, 사용자별 rate limit에는 429 등을 상황에 맞게 사용합니다. 클라이언트 재시도에는 backoff/jitter와 한도를 둡니다.

### 복구 중

- RDB/AOF, 복제와 failover 상황을 확인합니다. Redis 재시작이 반드시 전체 유실은 아닙니다.
- 최근 접근 빈도와 업무 중요도에 따라 hot set부터 적재합니다. 모든 인스턴스가 같은 전체 데이터를 적재하지 않도록 단일 작업자/partition/lease 등으로 분담합니다.
- Warm-up은 낮은 우선순위, 동시성/처리율 제한과 중단 조건을 둡니다. foreground·배치와 합산한 원본 예산을 넘지 않아야 합니다.
- Circuit Breaker half-open probe와 정상 트래픽을 제한적으로 확대합니다. 연결 성공만으로 전량 복귀하지 않습니다.
- TTL jitter와 갱신 분산으로 재발을 줄이고 장애 타임라인과 회복 판단을 기록합니다.

장애 대응의 타당성은 **관측 증상 → 원인 확인 → 보호 조치와 선택 근거 → 측정 결과 → 남은 위험**으로 평가합니다. 연결 실패, 데이터 유실, 원본 포화는 서로 다른 현상이므로 각각의 근거를 확인해야 합니다.

## 예상 꼬리 질문과 답변

**DB fallback을 하면 서비스가 유지되지 않나요?** 정상 캐시가 감당하던 요청 전체를 DB가 수용할 수 있다는 근거가 필요합니다. 처리 용량을 넘으면 DB까지 장애가 전파되므로 유효한 캐시, 요청 병합과 admission control을 함께 둡니다.

**인스턴스마다 20개 permit이면 충분한가요?** 10개 인스턴스라면 원본 작업이 최대 200개가 될 수 있고 autoscaling으로 더 늘어납니다. 배치/Warm-up까지 포함한 원본 예산과 인스턴스 변화에 맞춰 배분합니다. 숫자는 실측 용량과 SLO로 결정합니다.

**stale을 얼마나 허용하나요?** 비즈니스가 데이터별 허용 age를 정해야 합니다. 추천/상품 설명과 결제/권한을 같은 정책으로 묶지 않습니다. 만료된 재고를 확정 거래의 근거로 쓰지 않고 원본에서 검증합니다.

**Redis 분산 락으로 복구 부하를 막으면요?** Redis 장애 중에는 락도 사용할 수 없습니다. 복구 후에도 락은 다른 Key의 부하를 줄이지 않습니다. 락 미획득 요청의 L2 재확인, 제한 polling, stale/거절 정책이 필요합니다.

**lease가 만료되면요?** 느린 원본, GC pause와 네트워크 분리로 새 소유자가 생겨 중복 실행할 수 있습니다. 소유 token을 확인하는 원자적 해제로 남의 락 삭제는 막지만 늦은 쓰기까지 막지는 못합니다. fencing은 보호 대상이 단조 증가 token을 검증할 때 효과가 있습니다. UUID 소유 token과는 다릅니다.

**언제 정상화됐다고 판단하나요?** Redis hit ratio만 보지 않고 DB latency/active queries/pool pending, API P95/P99, 오류율, 거절/대기 수와 원본 QPS를 함께 봅니다. 정상 기준과 중단 조건은 기존 SLO와 실측 용량에 맞춥니다.

## 한계 / 주의점 및 답변 보완

cache-aside에서는 DB 갱신 전 시작한 조회가 무효화 후 오래된 값을 재게시할 수 있습니다. Single Flight와 분산 락은 이 정합성을 자동 해결하지 않으며 version/세대 기반 조건부 게시와 쓰기 경로 설계가 필요합니다. Redis Lua의 원자성도 DB와 Redis를 하나의 트랜잭션으로 만들지는 않습니다.

분산 락은 lease, 복제/failover와 시간 가정에 영향을 받으며 exactly-once의 일반적 보장이 아닙니다. 캐시 재생성의 일부 중복 읽기를 원본 예산 안에서 허용하는 설계가 더 단순할 수 있습니다. 결제 같은 부작용에는 원본 멱등성, 고유 제약과 트랜잭션을 별도로 설계합니다.

“자동 복구됩니다” 대신 보호 장치, 기능별 fallback, Warm-up 중단 조건과 실제 검증 범위를 답변합니다. 학습 예제는 Redis 장애/복구를 구현하거나 실측하지 않았습니다.

## 관련 문서 / 공식 참고 자료

- [SPOF와 고가용성](spof-high-availability.md): 역할별 장애 영향, 샤딩·복제·Failover의 구분
- [Circuit Breaker: 장애 감지, 요청 처리와 Slack 알림](circuit-breaker.md)
- [Cache Avalanche](../caching/cache-avalanche.md), [Caffeine / Redis](../caching/multi-level-cache.md), [Single Flight](../caching/single-flight.md)
- [Redis persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
- [Redis distributed locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)

원본 예산, 기능 축소와 복구 순서는 서비스 특성에 따른 설계 제안입니다.
