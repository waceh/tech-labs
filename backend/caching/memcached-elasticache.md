# Memcached와 AWS ElastiCache

## 질문 의도

“Memcached는 어디에 데이터를 저장하나요? AWS에서는 어떻게 분산하고 장애에 대응하나요?”

애플리케이션 로컬 메모리와 별도 캐시 프로세스, 샤딩과 복제, 엔진 기능과 관리형 서비스의 배포 구조를 구분할 수 있는지 평가합니다.

## 핵심 개념

### Q1. Memcached란 무엇인가요?

Memcached는 별도 프로세스로 실행되는 오픈소스 인메모리 Key-Value 캐시입니다. 애플리케이션의 JVM Heap이 아니라 Memcached 프로세스에 할당된 RAM을 사용합니다. 캐시 서버 자체를 실행하는 데 MySQL 등 별도 데이터베이스는 필요하지 않습니다. 업무의 원본 데이터는 별도 저장소에 두는 것이 일반적입니다. [Memcached 공식 문서](https://docs.memcached.org/)

```text
Spring Boot → Memcached Client → Network → Memcached Server → RAM
```

기본 사용 모델은 휘발성 캐시이며 일반적인 DB처럼 디스크 영속성을 전제로 하지 않습니다. Warm Restart 등 특수 기능이 있더라도 장애 시 원본에서 재생성 가능한 캐시라는 설계 원칙을 유지합니다. [Memcached FAQ](https://docs.memcached.org/userguide/faq/), [Warm Restart](https://docs.memcached.org/features/restart/)

### Q2. set과 get은 무엇을 하나요?

```python
# 특정 라이브러리의 실행 코드가 아닌 개념적 예시
cache.set('myKey', 'hi there', 3600)
cache.get('myKey')
```

`set`은 값을 저장하거나 덮어쓰고, `get`은 해당 Key의 값을 조회합니다. 위 예시는 TTL 3,600초를 의도하며 실제 인자 형식과 miss 반환값은 라이브러리별로 확인해야 합니다. 만료, eviction, 삭제나 노드 유실로 miss가 발생할 수 있습니다. TTL은 최소 보존 시간의 보장이 아닙니다. [Memcached User Guide](https://docs.memcached.org/userguide/)

Memcached 프로토콜의 expiration은 0이면 만료 시간 없음, 30일 이하면 상대 초, 30일을 초과하면 Unix timestamp로 해석됩니다. “만료 없음”이어도 메모리 압박이나 장애로 값이 사라질 수 있습니다. [Basic Protocol](https://docs.memcached.org/protocols/basic/)

### Q3. 로컬 캐시와 분산 캐시는 어떻게 다른가요?

| 구분 | 로컬 캐시(Caffeine 등) | 공유 캐시(Memcached, Redis 등) |
|---|---|---|
| 저장 위치 | 애플리케이션 프로세스 메모리 | 별도 캐시 프로세스/서버 |
| 네트워크 | 같은 프로세스 조회에는 불필요 | 일반적인 원격 조회에는 필요 |
| 인스턴스 간 공유 | 기본적으로 독립된 값 | 같은 Key 매핑과 직렬화 계약으로 공유 가능 |
| 주요 고려 | 값 차이, 중복 메모리, 무효화 | 네트워크 지연, 장애, 샤딩과 정합성 |

여러 애플리케이션이 하나의 클러스터를 이용해도 모든 노드가 모든 데이터를 복제하는 것은 아닙니다. L1과 L2를 함께 사용하면 [다단계 캐시의 freshness와 무효화](multi-level-cache.md)를 설계해야 합니다.

### Q4. Memcached와 Redis는 어떻게 다른가요?

| 구분 | 일반적인 Memcached 엔진 | Redis OSS 엔진 |
|---|---|---|
| 데이터 모델 | 단순 Key-Value와 저장된 bytes 중심 | String, Hash, List, Set 등 자료구조 |
| TTL | 지원 | 지원 |
| 영속성 | 기본적으로 휘발성 캐시 | RDB/AOF 옵션 지원 |
| 기본 복제 | 일반 서버 간 복제 미제공 | 복제 지원 |
| Pub/Sub | 미제공 | 지원 |
| 분산 방식 | 주로 클라이언트 Key 샤딩 | Redis Cluster 등 |

표는 엔진의 일반 기능 비교입니다. 관리형 서비스가 모든 엔진 옵션을 그대로 제공한다는 뜻은 아니며 실제 배포의 지원 기능을 확인해야 합니다. Redis의 영속성·복제도 설정과 실패 조건에 따라 데이터 손실 가능성이 달라집니다. [Redis 자료구조](https://redis.io/docs/latest/develop/data-types/), [영속성](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/), [복제](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/)

### Q5. AWS에서도 Memcached를 사용할 수 있나요?

Amazon ElastiCache는 Memcached, Redis OSS와 Valkey 엔진을 지원하는 관리형 서비스입니다. **ElastiCache for Memcached**를 사용할 수 있으며 노드 기반과 Serverless 배포를 구분해야 합니다. [AWS ElastiCache 개요](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/WhatIs.html)

### Q6. ElastiCache for Memcached는 데이터를 어떻게 분산하나요?

일반적인 노드 기반 클러스터에서는 클라이언트가 Key를 노드에 매핑해 해당 노드로 요청합니다. 클라이언트의 consistent hashing 지원과 노드 목록 갱신 정책을 확인합니다.

```text
Application → Memcached Client ─┬→ Node A: Key 일부
                               ├→ Node B: Key 일부
                               └→ Node C: Key 일부
```

Auto Discovery는 지원 클라이언트가 configuration endpoint를 통해 노드 구성을 알아내고 갱신하도록 돕습니다. 모든 클라이언트가 자동 지원하는 것은 아니며 데이터를 복제하거나 기존 Key를 자동 이동시키는 기능도 아닙니다. [AWS Auto Discovery](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/AutoDiscovery.html)

| 배포 | 연결과 분산 | 장애 관점 |
|---|---|---|
| 노드 기반 Memcached | 클라이언트가 노드를 알고 Key를 분배 | 기본 복제 없음. 노드 장애 시 해당 노드의 캐시 유실 |
| Serverless Memcached | 단일 endpoint 뒤 분산·용량을 AWS가 관리 | 복제된 Multi-AZ 구조로 노드/AZ 장애 영향 완화 |

노드를 여러 AZ에 배치하는 것과 같은 데이터를 여러 AZ에 복제하는 것은 다릅니다. Serverless 구조를 근거로 일반 Memcached 엔진에 복제 기능이 있다고 설명하지 않습니다. [AWS 장애 대응](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/disaster-recovery-resiliency.html)

Serverless는 TLS를 지원하는 클라이언트가 필요하며 노드 기반과 지원 명령·설정·비용 모델도 비교합니다. 높은 가용성은 모든 장애에서 캐시가 보존되거나 모든 요청이 성공한다는 보장이 아닙니다. [AWS 배포 옵션](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/WhatIs.deployment.html)

## 면접 답변 예시

> Memcached는 별도 프로세스의 RAM에 Key-Value 데이터를 저장하는 캐시이며 애플리케이션 로컬 Heap과 구분됩니다. 일반적인 노드 기반 구성은 클라이언트가 Key별 노드를 선택하고 노드 간 기본 복제를 제공하지 않습니다. AWS에서는 ElastiCache for Memcached를 사용할 수 있으며 Auto Discovery는 노드 구성 발견을 돕습니다. Serverless는 AWS가 관리하는 복제된 Multi-AZ 구조이므로 별도로 설명해야 합니다. 선택은 필요한 자료구조, 장애 허용 범위와 비용으로 판단하고 캐시 miss나 장애 시 원본 부하를 제한하는 설계가 필요합니다.

## 실무 적용과 설계 판단 기준

### Q7. Memcached 장애와 Cache Miss는 어떻게 처리하나요?

기본 Cache-aside 흐름은 캐시 조회 → 정상 miss이면 원본 조회 → 캐시 저장과 응답입니다. 실제 호출 실패는 정상 miss와 별도 기록하고 요청 deadline 안에서 fallback 가능 여부를 판단합니다.

- 동일 Key의 중첩 miss는 [Cache Stampede](cache-stampede.md)이며 [Single Flight](single-flight.md) 등으로 중복 로딩을 줄일 수 있습니다.
- 노드 유실·대량 만료로 여러 Key가 miss이면 [Cache Avalanche](cache-avalanche.md) 관점으로 전체 원본 동시성과 처리 예산을 제한합니다.
- timeout·연결 오류에는 [Circuit Breaker](../resilience/circuit-breaker.md)를 적용할 수 있습니다. 노드별 장애와 클러스터 전체 장애를 구분해 차단 범위를 정합니다.
- 유효한 L1과 허용된 stale, 제한된 원본 fallback, 기능 축소/빠른 실패 정책을 선택합니다. [Graceful Degradation](../resilience/redis-recovery.md)의 원본 보호 원칙은 재생성 가능한 Memcached에도 적용할 수 있습니다.
- 캐시 put 실패로 이미 성공한 원본 조회를 무제한 반복하지 않습니다. 늦은 stale write와 쓰기 후 무효화는 별도 정합성 정책이 필요합니다.

관측은 hit/miss와 호출 오류를 분리하고 eviction, 메모리, 연결, 응답 지연과 원본 QPS·포화를 함께 봅니다. 단순 hit ratio 하락만으로 노드 장애라고 단정하지 않습니다.

### Q8. Redis와 Memcached 중 무엇을 선택하나요?

단순 Key-Value 캐싱이면 Memcached를 검토할 수 있고 자료구조·복제·영속성 등 요구가 있으면 Redis OSS나 Valkey 등 대안을 비교합니다. 기능이 많다는 이유만으로 항상 더 적합한 것은 아닙니다. Item 크기, Key 분포, 지연, 실패 시 원본 용량, 운영 기능과 비용으로 평가합니다.

기존 캐시가 요구를 충족하면 다른 엔진을 추가하는 이익과 클라이언트·관측·장애 대응의 복잡성을 비교합니다. [ALB](../networking/load-balancer.md)와 [Route 53](../networking/route53-dns-routing.md)은 요청 전달/접속 대상 선택을 담당하며 Memcached는 데이터 캐싱을 담당하므로 대체 관계가 아닙니다.

## 예상 꼬리 질문과 답변

**Memcached를 3대로 샤딩하면 SPOF가 해결되나요?** 일반적인 노드 기반 구성에서는 각 노드가 서로 다른 Key를 맡으며 복제본이 자동으로 생기지 않습니다. 한 노드 장애 시 해당 Key의 캐시를 잃을 수 있습니다. 원본 fallback이 핵심 기능을 유지할지는 대량 miss 중 원본 용량과 부하 제어에 달려 있습니다. 노드 수 증가와 [고가용성 확보](../resilience/spof-high-availability.md)는 구분해야 합니다. Serverless의 복제된 Multi-AZ 구조는 위 배포 비교를 참고합니다.

**Q1. 노드를 추가하면 캐시 hit가 유지되나요?** Key 매핑이 바뀌면 다른 노드에서 miss가 발생할 수 있습니다. consistent hashing은 재매핑 범위를 줄일 수 있지만 자동 데이터 이동이나 무손실 확장을 보장하지 않습니다. 증설 시 원본 부하도 검증합니다.

**Q2. 클라이언트마다 Key 매핑이 다르면요?** 같은 Key가 다른 노드로 갈 수 있어 miss나 값 불일치가 생깁니다. 노드 목록, hash 규칙, Key namespace와 직렬화 계약을 맞춰야 합니다.

**Q3. 모든 노드를 LB 뒤에 두면 되나요?** 무작위 요청 분산으로 set과 get이 다른 노드에 가면 값이 있어도 miss가 됩니다. Key 기반 routing을 유지하는 클라이언트나 이를 지원하는 proxy가 필요합니다. 일반 HTTP LB 설정으로 해결되는 문제가 아닙니다.

**Q4. TTL을 길게 잡으면 데이터가 보존되나요?** 만료 빈도는 줄지만 eviction·노드 장애는 남고 freshness도 나빠집니다. Memcached를 유일한 원본으로 사용하는 근거가 되지 않습니다.

**Q5. CAS와 add로 무엇을 할 수 있나요?** `add`는 Key가 없을 때 저장하고 CAS는 읽은 뒤 값이 변경되지 않았을 때 갱신하는 경쟁 제어 수단입니다. 캐시 유실과 timeout이 있으므로 이것만으로 업무의 exactly-once나 안전한 분산 락을 보장하지 않습니다. [Memcached Protocol](https://docs.memcached.org/protocols/basic/)

## 한계 / 주의점 및 답변 보완

“분산 캐시”는 모든 데이터의 복제나 강한 정합성을 뜻하지 않습니다. 엔진 기능, 샤딩 방식과 배포 가용성을 구분합니다. Client의 miss 표현과 오류 처리가 다를 수 있으므로 timeout을 정상 miss로 숨기지 않아야 합니다.

이 문서는 개념과 설계 기준이며 실제 Memcached/ElastiCache 생성·연결·장애 시험 결과가 아닙니다.

## 관련 문서 / 공식 참고 자료

- [다단계 캐시](multi-level-cache.md), [Single Flight](single-flight.md), [Cache Stampede](cache-stampede.md), [Cache Avalanche](cache-avalanche.md)
- [Circuit Breaker](../resilience/circuit-breaker.md), [Redis 장애 복구와 Graceful Degradation](../resilience/redis-recovery.md)
- [Memcached 공식 사이트](https://memcached.org/), [User Guide](https://docs.memcached.org/userguide/), [Basic Protocol](https://docs.memcached.org/protocols/basic/)
- [AWS ElastiCache](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/), [Auto Discovery](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/AutoDiscovery.html), [배포 옵션](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/WhatIs.deployment.html)
