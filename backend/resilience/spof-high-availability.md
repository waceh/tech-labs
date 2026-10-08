# SPOF와 고가용성(High Availability)

## 질문 의도

“서버가 한 대면 SPOF인가요? 여러 대로 샤딩하면 고가용성이 확보되나요?”

구성 요소 수가 아니라 장애가 핵심 기능에 미치는 영향으로 단일 장애점을 판단하고, 확장·복제·장애 전환을 구분할 수 있는지 평가합니다.

## 핵심 개념

### SPOF란 무엇인가요?

SPOF(Single Point of Failure)는 하나의 구성 요소 장애가 시스템 전체 또는 핵심 기능 중단으로 이어지는 단일 장애점입니다. 판단 범위는 사용자 기능과 허용 장애 조건으로 정의합니다.

애플리케이션이 여러 대여도 단일 LB 프로세스에만 진입 경로를 의존하면 그 LB가 SPOF일 수 있습니다. 유일한 DB가 멈춰 핵심 조회·쓰기가 불가능해지는 경우도 해당합니다. 하나의 논리 endpoint가 반드시 하나의 물리 인스턴스를 의미하는 것은 아닙니다. [AWS LB 구조](../networking/load-balancer.md)처럼 실제 구성과 장애 격리 범위를 확인해야 합니다.

### 서버가 한 대라면 무조건 SPOF인가요?

아닙니다. 동일한 Redis 한 대라도 역할과 대체 경로에 따라 달라집니다.

| 구성 요소의 역할 | 장애 영향 | 판단 기준 |
|---|---|---|
| 재생성 가능한 캐시 | miss/지연 증가, 원본 fallback | 원본 용량과 fallback이 핵심 기능을 유지하는지 |
| 세션의 유일한 저장소 | 세션 조회 실패, 로그인 유지·인증 기능 영향 | 세션 복구·재인증 등 대체 경로가 요구를 충족하는지 |
| 유일한 원본 DB | 핵심 읽기/쓰기 중단 | 대체 저장소나 제한된 서비스 경로가 있는지 |
| 추천 등 선택적 기능 | 해당 기능 축소 | 서비스 전체와 해당 기능의 가용성을 따로 평가 |

“DB로 우회할 수 있다”는 코드만으로 캐시가 SPOF가 아니라고 단정하지 않습니다. 장애 중 DB가 요청을 감당하지 못하면 간접적으로 전체 중단을 유발할 수 있습니다. [캐시 장애 중 원본 보호](redis-recovery.md)가 실제로 작동하는지 검증해야 합니다.

### SPOF를 어떻게 완화하나요?

| 개념 | 역할 | 단독 적용의 한계 |
|---|---|---|
| Redundancy | 대체 가능한 구성 요소 확보 | 대상이 있어도 전환되지 않거나 같은 장애에 노출될 수 있음 |
| Health Check / 장애 감지 | 실패나 기능 저하 식별 | 오탐·감지 지연, 특정 경로만 확인하는 문제 |
| Failover | 장애 시 대체 구성 요소로 역할/트래픽 전환 | 데이터 손실, 연결 복구와 대체 용량 검증 필요 |
| HA | 정의한 장애 조건에서 서비스 가용성 유지 | 모든 장애에서 무중단·무손실을 뜻하지 않음 |

다중화, 독립된 장애 영역, 감지, 전환, 데이터 정합성과 복구 절차를 함께 설계합니다. 같은 AZ·네트워크·공유 의존성을 사용하면 여러 인스턴스도 동시에 영향을 받을 수 있습니다. [AWS 장애 격리 지침](https://docs.aws.amazon.com/wellarchitected/latest/reliability-pillar/rel_fault_isolation_multiaz_region_system.html)

### 샤딩과 복제의 차이는 무엇인가요?

| 구분 | Sharding | Replication |
|---|---|---|
| 주요 목적 | 데이터·트래픽 분산과 확장 | 복제본을 통한 가용성·데이터 보호, 읽기 확장 |
| 데이터 배치 | 노드/Shard마다 다른 데이터 | 같은 데이터의 복사본 |
| 장애 시 | 해당 Shard 데이터 접근이 불가능할 수 있음 | 복제 상태와 전환 절차에 따라 서비스 지속 가능 |

Redis Cluster는 Primary 간 샤딩과 각 Primary의 Replica를 함께 구성할 수 있습니다. Replica가 실제로 구성되어 있는지와 실패 시 승격 조건을 확인해야 합니다. [Redis Cluster 사양](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/)

Replication은 데이터를 다른 노드에 전달하고 Failover는 대체 노드에 역할과 접속 경로를 전환하는 절차입니다. 샤딩은 복제를 대신하지 않고 복제만으로 자동 Failover가 생기지도 않습니다. 구체적인 사례는 [Memcached 샤딩](../caching/memcached-elasticache.md)과 [Redis 복제·Failover](redis-recovery.md)를 참고합니다.

## 면접 답변 예시

> SPOF는 하나의 구성 요소 장애가 시스템 전체나 핵심 기능 중단으로 이어지는 단일 장애점입니다. 인스턴스 수보다 해당 기능의 의존성과 대체 경로로 판단합니다.

추가 설명: 이중화와 독립된 장애 영역, 장애 감지와 Failover로 위험을 줄일 수 있지만 샤딩이나 서버 수 증가만으로 해결되지는 않습니다. 캐시 fallback도 원본이 장애 중 부하를 감당해야 효과가 있습니다. 장애 전환 시간, 데이터 손실 가능성과 공통 의존성까지 검증해야 합니다.

## 실무 적용과 설계 판단 기준

1. 핵심 기능별로 진입점, 인증, 애플리케이션, 캐시, DB와 외부 API 의존성을 그립니다. 시스템 전체 가용성과 특정 기능 가용성을 구분합니다.
2. 프로세스·노드·AZ·리전·네트워크 단절 등 장애 가정을 정하고 각 경우 남는 데이터와 용량을 확인합니다.
3. 감지 → 대상 선택/승격 → 트래픽 전환 → 재연결 → 처리 재개까지 역할과 시간을 정의합니다. [LB Health Check](../networking/load-balancer.md)와 [DNS Failover](../networking/route53-dns-routing.md)의 적용 범위는 다릅니다.
4. RTO(복구 목표 시간), RPO(복구 지점 기준으로 허용하는 데이터 손실의 시간 범위, 예: 최대 5분)와 서비스 SLO를 정하고 복제 방식·백업·전환 정책에 맞춰 검증합니다. [AWS 복구 계획](https://docs.aws.amazon.com/wellarchitected/latest/framework/rel-13.html)
5. 장애 후 대체 노드·AZ가 Peak 부하를 감당하는지 확인하고 retry storm, cold cache와 Warm-up을 제한합니다.
6. 장애 전환과 원래 구성으로의 복귀를 시험합니다. 네트워크 단절 시 이전 Primary의 쓰기, 늦은 결과와 중복 재시도 등도 검증합니다.

## 예상 꼬리 질문과 답변

**Memcached를 3대로 샤딩하면 SPOF가 해결되나요?** 용량과 부하는 분산되지만 일반적인 노드 기반 구성에 복제가 생기는 것은 아닙니다. A가 맡은 Key는 A 장애 시 접근하지 못할 수 있습니다. 서비스 유지 여부는 원본 fallback과 용량에 달려 있습니다. [Memcached 문서](../caching/memcached-elasticache.md)에서 Serverless의 복제된 Multi-AZ 구조와 구분합니다.

**Redis Replica가 있으면 자동으로 전환되나요?** 복제와 Failover는 다릅니다. Sentinel, Cluster 또는 관리형 서비스의 지원 구성과 클라이언트의 새 Primary 발견·재연결이 필요합니다. 비동기 복제에서는 일부 최신 데이터가 유실될 수 있습니다. [Redis 복구 문서](redis-recovery.md)

**캐시 장애 후 대량 miss로부터 DB를 어떻게 보호하나요?** [원본 보호와 fallback 정책](redis-recovery.md)을 적용합니다. HA 평가에서는 캐시를 우회한 뒤에도 핵심 기능이 유지되는지와 잔여 원본 용량을 검증합니다. 다수 Key miss는 [Cache Avalanche](../caching/cache-avalanche.md)와 연결됩니다.

**다중 AZ로 배치해도 남는 SPOF는 무엇인가요?** 공유된 단일 DB/인증 서비스, 특정 AZ의 유일한 NAT·proxy, 공통 설정·배포·자격 증명 등에 의존하면 여전히 광범위한 장애가 생길 수 있습니다. 공통 원인 장애는 단일 인스턴스 SPOF와 완전히 같은 개념은 아니지만 HA 평가에서 함께 점검합니다.

**관리형 LB의 DNS 이름이 하나면 SPOF인가요?** 논리 이름 개수로 판단하지 않습니다. AWS ELB는 활성화한 AZ에 LB 노드를 구성합니다. AZ 배치, Target 용량과 DNS/client 동작까지 확인해야 합니다. [ELB 동작 방식](https://docs.aws.amazon.com/elasticloadbalancing/latest/userguide/how-elastic-load-balancing-works.html)

**복제본이 있으니 백업은 필요 없나요?** 잘못된 쓰기·삭제가 복제본에도 전파될 수 있습니다. 가용성 확보용 복제와 과거 데이터 복구용 백업은 역할이 다르며 복원 시험과 보존 정책이 필요합니다.

## 한계 / 주의점 및 답변 보완

HA, 무손실, 강한 정합성, exactly-once는 서로 다른 보장입니다. Failover 중 일시적 오류나 재시도가 발생할 수 있고 네트워크 단절 시 안전한 쓰기를 위해 일부 가용성을 제한할 수도 있습니다. “이중화했으므로 SPOF가 없다” 대신 보호하는 장애 범위와 남은 의존성을 명시합니다.

이 문서는 설계 기준과 가상 시나리오이며 실제 장애 주입, RTO/RPO 측정이나 Failover 시험 결과가 아닙니다.

## 관련 문서 / 공식 참고 자료

자료 확인일: 2026-10-08. 제품 기능은 링크된 공식 latest/current 문서 기준이며, 실제 배포의 엔진·클라이언트·프레임워크 버전과 지원 설정을 별도로 확인합니다.

- [Memcached와 ElastiCache](../caching/memcached-elasticache.md), [Redis 장애 복구](redis-recovery.md), [Cache Avalanche](../caching/cache-avalanche.md)
- [Load Balancer](../networking/load-balancer.md), [Route 53](../networking/route53-dns-routing.md), [Circuit Breaker](circuit-breaker.md)
- [AWS 장애 격리](https://docs.aws.amazon.com/wellarchitected/latest/reliability-pillar/rel_fault_isolation_multiaz_region_system.html), [복구 계획](https://docs.aws.amazon.com/wellarchitected/latest/framework/rel-13.html)
- [Redis Cluster](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/)
