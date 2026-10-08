# DynamoDB: 장점, 접근 패턴과 데이터베이스 선택

## 질문 의도

“DynamoDB의 장점은 무엇인가요? MongoDB와 무엇이 다르고, 사용할 때 어떤 점을 주의해야 하나요?”

관리형 서비스의 편의성뿐 아니라 접근 패턴, Key 분포, 조회 유연성, 정합성과 비용을 기준으로 선택할 수 있는지 평가합니다.

## 핵심 개념

DynamoDB는 AWS의 완전 관리형 serverless Key-Value / Document 데이터베이스입니다. 분산 저장과 인프라 운영 부담을 줄이고 Key 기반 접근에서 낮은 지연을 제공하도록 설계되었습니다. 한 자릿수 밀리초 수준이라는 서비스 특성이 모든 조회와 부하에서 일정 지연이나 무제한 처리량을 보장한다는 뜻은 아닙니다. [AWS 개요](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Introduction.html)

| 장점 | 의미 | 확인할 조건 |
|---|---|---|
| 확장성 | AWS가 분산 저장과 용량 관리 제공 | Key 편향, 처리량 한도와 급격한 부하 증가 |
| 낮은 조회 지연 | Key 기반 접근의 예측 가능한 성능 | Item 크기, 인덱스, 정합성과 재시도 |
| 운영 편의 | 서버·복제 등 인프라 관리 부담 감소 | 백업·복구 목표, 권한과 데이터 모델은 여전히 설계 필요 |
| 이벤트 연계 | Streams와 Lambda 등으로 변경 후 처리 구성 | 중복·지연·실패 복구 정책 |

기본 Key는 Partition Key 단독 또는 Partition Key + Sort Key 조합입니다. `Query`는 특정 Partition Key 값과 Sort Key 조건을 활용합니다. 추가 접근 패턴은 보조 인덱스로 설계합니다. `Scan`도 가능하지만 빈번한 온라인 조회의 기본 경로로 삼으면 비용과 지연이 커질 수 있습니다.

### DynamoDB와 MongoDB 비교

| 구분 | DynamoDB | MongoDB |
|---|---|---|
| 데이터 모델 | Key-Value / Document | Document |
| 조회 설계 | Partition/Sort Key와 인덱스 중심 | 다양한 필드 조건과 Aggregation |
| 인덱스 | LSI, GSI | Secondary, Compound 등 다양한 인덱스 |
| 확장 | AWS 관리형 분산 | Shard Key 기반 sharding |
| 운영 모델 | AWS 관리형 서비스 | 자체 구축 또는 관리형 Atlas |
| 선택 기준 | 명확한 접근 패턴과 Key 기반 대규모 읽기/쓰기 | 유연한 조건 조회와 Document 처리 요구 |

MongoDB도 [Shard Key 설계](https://www.mongodb.com/docs/manual/sharding/)와 [인덱스](https://www.mongodb.com/docs/manual/indexes/)가 중요합니다. 임의 Query가 항상 빠른 것은 아닙니다. [Atlas](https://www.mongodb.com/docs/atlas/)도 관리형 서비스이므로 운영 부담 비교는 MongoDB 자체 구축과 Atlas를 구분해야 합니다.

### Amazon DocumentDB와 MongoDB 비교

MongoDB는 데이터베이스 엔진이고 Atlas는 MongoDB의 관리형 서비스입니다. Amazon DocumentDB는 AWS 자체 엔진으로 MongoDB API 호환성을 제공하며 MongoDB 엔진을 그대로 호스팅하는 서비스가 아닙니다.

드라이버 연결만으로 기능·성능의 동등성이 보장되지 않습니다. 이전 대상 버전의 Query/Aggregation, 인덱스, 트랜잭션, retryable writes와 실행 계획을 실제 작업으로 검증해야 합니다. “트랜잭션이 없다”처럼 버전 차이를 무시한 단정도 피합니다. [DocumentDB 기능 차이](https://docs.aws.amazon.com/documentdb/latest/devguide/functional-differences.html)

## 면접 답변 예시

> DynamoDB의 장점은 분산 인프라 운영 부담을 줄이면서 명확한 Key 기반 접근 패턴을 확장하기 쉽다는 점입니다. 다만 Partition Key 편향이나 비효율적인 Scan은 성능과 비용 문제를 만들 수 있으므로 접근 패턴을 먼저 정하고 Key와 인덱스를 설계해야 합니다.

추가 설명: MongoDB는 다양한 필드 조건과 Aggregation에 유연하며 Atlas를 통한 관리형 운영도 가능합니다. 따라서 조회 요구, 정합성, Key 분포와 비용으로 선택합니다. Amazon DocumentDB는 MongoDB API 호환 별도 엔진이므로 이전 시 기능과 실행 계획 검증이 필요합니다.

## 실무 적용과 설계 판단 기준

1. 조회·쓰기별 Key, 조건, 정렬, 결과 크기와 빈도를 정의합니다. 신규 접근 패턴의 추가 가능성도 확인합니다.
2. Partition Key의 값 개수뿐 아니라 실제 요청 분포를 확인합니다. 많은 Key가 있어도 일부 Key에 부하가 집중될 수 있습니다.
3. Sort Key와 GSI/LSI로 필요한 조회 경로를 설계하고 중복 저장·인덱스 쓰기 비용을 평가합니다.
4. 읽기 정합성과 경쟁 쓰기 정책을 정합니다. 조건부 쓰기나 트랜잭션이 필요한 경로를 구분합니다.
5. Peak 부하에서 throttling, tail latency, 소비 용량과 비용을 검증하고 SDK 재시도까지 포함한 deadline을 정합니다.

Adaptive capacity는 편향을 완화하지만 테이블과 Partition의 용량 제약을 제거하지 않습니다. 극단적 Hot Key는 write sharding으로 분산할 수 있지만 읽기 fan-out과 집계 비용이 생깁니다. On-demand도 throttling 가능성을 없애지는 않습니다. [Key 설계](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-partition-key-design.html), [Adaptive capacity](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/burst-adaptive-capacity.html)

Streams로 저장소나 캐시를 갱신한다면 중복·최신 version·실패 재처리를 고려합니다. 관련 설계 기준은 [멱등성과 최신 상태 판별](../messaging/kafka-offset-replay.md), [캐시 무효화 정합성](../caching/multi-level-cache.md)과 연결됩니다. Kafka의 전달 특성을 Streams에 그대로 적용한다는 의미는 아닙니다.

## 예상 꼬리 질문과 답변

**GSI와 LSI는 어떻게 다른가요?** GSI는 원본과 독립적으로 Key schema를 정의하며 다른 Partition Key와 선택적 Sort Key를 사용할 수 있습니다. 원본과 반드시 다른 Partition Key여야 하는 것은 아닙니다. LSI는 원본 Partition Key를 유지하고 다른 Sort Key를 사용합니다. LSI는 테이블 생성 시 정의하며 GSI는 이후 추가할 수 있습니다. Projection·저장 비용·용량도 비교합니다. [AWS Secondary indexes](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/SecondaryIndexes.html)

**모든 읽기에 강한 일관성을 쓸 수 있나요?** 테이블과 LSI는 강한 일관성 읽기를 선택할 수 있지만 GSI와 Streams 읽기는 eventual consistency입니다. 갱신 직후 GSI에 최신 값이 보이지 않을 수 있으므로 중요한 확인 경로를 따로 설계합니다. Global Tables는 선택한 다중 리전 정합성 모드와 지원 조건을 별도로 확인합니다. [AWS Read consistency](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html)

**FilterExpression으로 다양한 조건을 조회하면 되나요?** 읽은 뒤 결과를 걸러내므로 읽기 용량 소비를 줄이는 인덱스 대체 수단이 아닙니다. 자주 사용하는 조건은 Key/인덱스로 표현합니다. [AWS Query filter](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Query.FilterExpression.html)

**접근 패턴이 바뀌면 어떻게 하나요?** 추가 인덱스나 별도 조회 모델을 설계할 수 있지만 중복 저장, backfill과 쓰기 비용이 늘 수 있습니다. 요구 변화 비용을 다른 데이터베이스와 비교해야 합니다.

**조건부 쓰기로 무엇을 보호하나요?** 예상 version과 일치할 때만 갱신하거나 없는 Item만 생성해 경쟁 쓰기·중복 처리를 제어할 수 있습니다. 조건 실패는 충돌과 재시도 정책을 구분해서 처리합니다. [AWS Condition expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.ConditionExpressions.html)

## 한계 / 주의점 및 답변 보완

“관리형이므로 데이터 모델을 고려할 필요가 없다”거나 “NoSQL이므로 정합성 요구를 고려하지 않아도 된다”는 설명은 부정확합니다. 접근 패턴과 데이터 정합성에 맞는 설계가 필요합니다. 비교 대상 버전, 실제 Query와 비용 모델을 명시해야 합니다.

이 문서는 선택과 설계 기준이며 실제 AWS 리소스 생성·부하 시험·마이그레이션 결과를 포함하지 않습니다.

## 관련 문서 / 공식 참고 자료

자료 확인일: 2026-10-08. 제품 기능은 링크된 공식 latest/current 문서 기준이며, 실제 배포의 엔진·클라이언트·프레임워크 버전과 지원 설정을 별도로 확인합니다.

- [다단계 캐시](../caching/multi-level-cache.md), [Offset Replay와 멱등성](../messaging/kafka-offset-replay.md)
- [AWS DynamoDB 개요](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Introduction.html), [DynamoDB Streams](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Streams.html)
- [MongoDB Sharding](https://www.mongodb.com/docs/manual/sharding/), [MongoDB Atlas](https://www.mongodb.com/docs/atlas/)
- [Amazon DocumentDB 기능 차이](https://docs.aws.amazon.com/documentdb/latest/devguide/functional-differences.html)
