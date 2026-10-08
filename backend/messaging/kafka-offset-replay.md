# Kafka Offset Replay와 멱등성

## 질문 의도

“처리 누락은 어떻게 복구하나요? offset을 되돌려도 중복과 정합성이 안전한가요?”

재전달과 업무 복구를 구분하고 replay 범위, 데이터 보존, 멱등성과 순서를 설명할 수 있는지 평가합니다.

## 핵심 개념

Offset Replay는 보존된 로그의 과거 위치부터 다시 읽어 처리를 재실행하는 방식입니다. offset은 Topic/Partition별 위치이고 Group의 committed offset은 일반적으로 다음에 읽을 위치입니다. DB 변경을 되돌리는 rollback과는 다릅니다.

처리 후 commit하면 DB 반영 후 commit 전 장애에서 재전달될 수 있습니다. 처리 전 commit하면 장애 시 업무 반영이 빠질 수 있습니다. At-least-once 처리에서는 재전달을 예상하고 업무 멱등성을 설계해야 합니다. [Kafka 전달 의미](https://kafka.apache.org/43/design/design/)

정상 [Rebalancing](kafka-partition-rebalancing.md)은 그 자체로 replay 사유가 아닙니다. 오류 해결 후에도 처리 누락이 없다면 불필요하게 offset을 되돌릴 필요가 없습니다.

## 면접 답변 예시

> Replay는 보존된 이벤트를 재처리하는 복구 수단입니다. 먼저 누락이나 잘못된 결과의 범위를 확인하고 원인을 수정한 뒤 대상을 선정합니다.

추가 설명: 같은 Group의 offset을 변경하면 Consumer를 중지하고 기존 offset을 보관한 후 변경 계획을 검토합니다. 재처리에는 멱등성뿐 아니라 version 기반 최신성 검사와 외부 부작용 제어가 필요합니다. At-least-once는 전달 의미이며 최종 정합성을 자동 보장하지 않습니다. 완료 여부는 Lag 감소뿐 아니라 누락·중복·최종 상태의 대조로 판단합니다.

## 실무 적용과 설계 판단 기준

### 복구 절차

1. 실패 원인과 누락된 업무 결과를 확인합니다. 처리 버그, 역직렬화 실패, 조기 commit과 외부 저장소 실패를 구분합니다.
2. 코드·설정·Schema 문제를 수정하고 재현 데이터로 확인합니다. [Schema Registry](kafka-schema-registry.md) 오류는 offset 변경으로 해결되지 않습니다.
3. Partition별 대상 위치, 종료 경계와 보존 여부를 정합니다. 시간 기반 위치는 후보를 찾는 수단이며 정확한 업무 피해 범위와 같지는 않습니다.
4. 같은 Group을 reset할 경우 해당 Consumer를 중지하고 기존 offset을 export/보관합니다. dry-run으로 대상과 변경량을 확인합니다. [Kafka Group offset 운영](https://kafka.apache.org/43/operations/basic-kafka-operations/)
5. 영향이 크면 별도 Group과 격리된 결과 저장소로 먼저 검증합니다. Group만 달라도 같은 DB/API에 쓰면 업무 영향은 격리되지 않습니다.
6. 제한된 처리율로 replay하며 실시간 처리와 합산한 DB/API 예산, 중단 조건과 진행 위치를 기록합니다.
7. 종료 경계와 실제 결과를 대조한 뒤 정상 경로로 복귀합니다. 잘못된 부작용은 offset 복원만으로 되돌릴 수 없습니다.

retention으로 삭제됐거나 compacted topic에서 과거 변경이 제거됐다면 원하는 전체 이력을 replay할 수 없습니다. 백업·원본 스냅샷·별도 로그를 통한 복구 가능성을 확인합니다. [Kafka Log compaction](https://kafka.apache.org/43/design/design/#log-compaction)

### 중복과 순서의 안전성

| 상황 | 설계 예시 | 주의점 |
|---|---|---|
| 동일 이벤트 재전달 | 안정적인 event ID와 unique constraint/dedup 기록 | 중복 기록과 업무 반영을 원자적으로 처리해야 함 |
| 완전한 상태 동기화 | entity별 단조 version을 비교해 조건부 교체 | 이벤트가 해당 version의 완전한 상태를 포함할 때 적용. delta에 그대로 사용하지 않음 |
| 삭제 후 과거 이벤트 | tombstone/삭제 version 보존 | 삭제 흔적이 없으면 데이터가 부활할 수 있음 |
| 결제·메일·외부 API | 수신 측 idempotency key, outbox/inbox 등 | 로컬 dedup만으로 외부 호출과 DB 갱신의 원자성이 생기지 않음 |
| 증분 연산 | 적용 여부와 증분 반영의 원자적 기록 | 잔액 증가·수량 감소는 재적용 시 값이 달라짐 |

완전한 상태를 담은 version 12가 저장된 뒤 version 11이 도착하면 조건부 갱신으로 거절할 수 있습니다. 반면 version 11의 `+10`, version 12의 `-3` 같은 delta는 중간 이벤트를 건너뛰면 결과가 틀립니다. delta는 중복 판별과 순서·누락 감지, 필요하면 gap 대기/재조회나 원본 상태 재동기화를 설계합니다. timestamp는 시계 오차와 동일 시각 때문에 충분한 순서 기준이 아닐 수 있습니다. [DynamoDB 조건부 쓰기](../database/dynamodb.md)도 구현 수단 중 하나입니다. 이러한 정책은 적용 대상의 상태 모델에 맞춰 검증해야 하는 설계 예시입니다.

### 잘못 반영된 동일 version의 보정

처리 버그로 version 12의 결과가 잘못 저장돼도 dedup 기록이나 `incomingVersion > storedVersion` 조건은 수정된 version 12의 replay를 막을 수 있습니다. 원인을 고친 것과 기존 결과를 고친 것은 별도 작업입니다.

- 전체 재구축은 별도 저장소/namespace와 별도 dedup 기록에서 replay하고 검증 후 전환합니다. 같은 DB에 같은 dedup 조건으로 쓰는 새 Group만으로는 해결되지 않습니다.
- 선택적 보정은 대상 ID·version·예상 기존 값과 수정 값을 명시하고, 실시간 쓰기와 충돌하지 않도록 조건부 쓰기나 잠깐의 격리를 사용합니다. 보정 기록과 결과 대조를 남깁니다.
- 외부 결제·메일 등의 부작용은 무작정 다시 실행하지 않습니다. 수신 측 상태 확인과 업무상 보상/보정 절차를 판단합니다.

이는 상태 모델에 따른 설계 예시이며 보호 조건을 전역 해제하거나 dedup 기록을 일괄 삭제하라는 의미는 아닙니다.

## 예상 꼬리 질문과 답변

**At-least-once면 최종 정합성이 보장되나요?** 아닙니다. 잘못된 이벤트, 오래된 값의 덮어쓰기, 삭제 누락과 영구 실패가 있으면 수렴하지 않습니다. 중복 안전성, 최신성 판단과 실패 복구가 필요합니다.

**Eventual Consistency이면 중복을 허용해도 되나요?** 최종 상태가 수렴하는 연산과 규칙이 있어야 합니다. 선언만으로 증분 연산이나 외부 부작용이 안전해지지 않습니다.

**Producer idempotence로 충분하지 않나요?** Producer 재시도의 중복 제어와 Consumer 업무 멱등성은 다릅니다. replay나 DB 반영 후 commit 전 장애의 재실행을 해결하지 않습니다.

**Exactly-once면 DB 중복도 없어지나요?** Kafka 내부 read-process-write에서는 transaction으로 출력과 소비 offset을 원자적으로 반영하고, 자동 commit을 끄며 downstream Consumer가 `isolation.level=read_committed`로 중단된 transaction의 출력을 읽지 않도록 구성해야 합니다. transaction abort·재시작·Rebalancing 처리도 검증합니다. 외부 DB/API까지 자동으로 같은 트랜잭션이 되지는 않습니다. 시스템 경계와 수신 측 멱등성을 확인합니다. [Kafka 전달 의미](https://kafka.apache.org/43/design/design/)

**auto.offset.reset을 earliest로 바꾸면 replay되나요?** 유효한 committed offset이 있으면 그 위치가 사용됩니다. 이 설정은 offset이 없거나 유효하지 않을 때의 정책이며 명시적인 replay를 대체하지 않습니다. [Consumer 설정](https://kafka.apache.org/43/configuration/consumer-configs/)

**DLQ로 옮기면 복구가 끝났나요?** 실패 격리일 뿐입니다. 원인 수정, 재처리, 결과 대조와 미해결 이벤트 관리가 있어야 완료됩니다. 뒤 이벤트를 계속 처리하면 순서 영향도 확인해야 합니다.

## 한계 / 주의점 및 답변 보완

dedup 보존 기간이 replay 범위보다 짧으면 과거 부작용이 다시 실행될 수 있습니다. offset은 다른 Partition 사이의 entity version이 아니며 재발행 시에도 달라집니다. 식별자·최신성·보존 기간은 별도 계약으로 설계합니다.

이 문서는 복구 판단과 절차이며 실제 Group의 offset 변경이나 데이터 재처리 결과가 아닙니다.

## 관련 문서 / 공식 참고 자료

자료 확인일: 2026-10-08. Kafka 동작은 Apache Kafka 4.3 문서 기준입니다.

- [Partition과 Rebalancing](kafka-partition-rebalancing.md), [Schema Registry 호환성](kafka-schema-registry.md)
- [DynamoDB](../database/dynamodb.md), [캐시의 version 기반 정합성](../caching/multi-level-cache.md)
- [Kafka 전달 의미와 Log compaction](https://kafka.apache.org/43/design/design/)
- [Consumer Group 운영](https://kafka.apache.org/43/operations/basic-kafka-operations/), [Consumer 설정](https://kafka.apache.org/43/configuration/consumer-configs/)
