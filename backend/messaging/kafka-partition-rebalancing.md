# Kafka Partition 수와 Rebalancing

## 질문 의도

“Partition 수는 어떻게 결정하나요? 운영 중 증설하면 순서와 Consumer에 어떤 영향이 있나요?”

병렬 처리 용량과 운영 비용을 함께 판단하고 정상 할당 변경과 처리 장애를 구분할 수 있는지 평가합니다. 일반적인 Consumer Group 기준이며 Share Group은 범위에 포함하지 않습니다.

## 핵심 개념

Partition은 Topic 로그를 나누는 단위이며 순서는 Partition 내부에서 정의됩니다. 동일 Group에서는 한 Partition을 동시에 한 Consumer에게 할당합니다. 하나의 Consumer가 여러 Partition을 맡을 수 있고 여러 Group은 독립적으로 같은 Topic을 소비할 수 있습니다. [Apache Kafka 개요](https://kafka.apache.org/43/getting-started/introduction/)

| 관점 | 판단할 내용 |
|---|---|
| Producer | 초당 건수와 bytes, Peak 지속 시간, Key 편향 |
| Consumer | Partition당 처리량, downstream 지연, CPU/메모리와 처리 순서 |
| 목표 | 허용 end-to-end 지연, backlog 해소 시간과 장애 후 회복 여유 |
| Broker | 저장·복제·네트워크 용량과 Partition 관리 비용 |

Partition 수는 Consumer 병렬성의 상한이며 처리량을 자동 보장하지 않습니다. Consumer를 늘려도 Hot Partition이나 DB 병목은 남을 수 있습니다. Partition 수와 replication factor는 다른 설정입니다.

Rebalancing은 Group의 Partition 할당을 조정하는 과정입니다. 멤버 가입/이탈, 실패 감지와 구독 Partition 변경 등이 계기가 됩니다. 처리 중단 범위는 eager/cooperative 할당과 사용하는 rebalance protocol에 따라 달라집니다. 모든 Rebalancing이 전체 소비를 같은 방식으로 중단하는 것은 아닙니다. [Consumer Rebalance Protocol](https://kafka.apache.org/43/operations/consumer-rebalance-protocol/)

## 면접 답변 예시

> Partition 수는 Peak 유입량, Consumer의 실측 처리량과 목표 backlog 해소 시간을 기준으로 정합니다. 같은 Group의 병렬성은 Partition 수로 제한되지만 downstream 용량과 Key 편향도 확인해야 합니다. 기존 Topic의 Partition 수는 직접 줄일 수 없으므로 초기 설계에서 확장 여유와 관리 비용을 함께 고려합니다. 증설하면 할당과 Key의 Partition 매핑이 바뀔 수 있으며 기존 데이터가 새 Partition으로 이동하지는 않습니다. 정상 Rebalancing은 유효한 committed offset에서 재개하므로 임의 offset reset이 필요하지 않습니다. 새 Partition의 초기 offset 정책과 미완료 작업의 재처리 가능성은 별도로 확인합니다.

## 실무 적용과 설계 판단 기준

### 처리량과 backlog 해소 시간

단순 모델에서 유입률 `λ`, 전체 지속 처리율 `μ`, backlog `B`라 하면 `μ > λ`일 때 해소 시간은 대략 `B / (μ - λ)`입니다. 목표 시간 `T` 안에 해소하려면 `μ ≥ λ + B/T`가 필요합니다. Key 편향과 외부 호출·재시도를 포함한 실측으로 검증해야 하는 용량 추정식입니다.

가상 예시로 지속 유입 1,000건/s, backlog 360만 건, 목표 1시간이면 최소 2,000건/s의 처리가 필요합니다. 안전 여유와 장애 시 용량은 별도로 확보합니다. 특정 시점에 유입이 집중되는 패턴은 일평균보다 Peak 강도·지속 시간과 이후 해소 목표를 기준으로 평가합니다.

증설 전 실제 병목이 Consumer 병렬성인지 확인합니다. 전체 Lag뿐 아니라 Partition별 Lag, 유입/처리율, 오래 대기한 이벤트의 age와 downstream 포화를 관측합니다.

### 증설 시 확인할 변화

| 변화 | 영향과 대응 |
|---|---|
| Partition 할당 변경 | 프로토콜에 따른 지연. 미완료 작업과 commit 경계 점검 |
| Key 기반 매핑 변경 | 같은 Key의 과거/신규 이벤트가 다른 Partition에 존재할 수 있음 |
| 기존 데이터 유지 | 기존 backlog는 새 Partition으로 자동 재분배되지 않음 |
| 새 Partition에 committed offset 없음 | `auto.offset.reset` 등 초기 위치 정책 확인 |
| Consumer 증설 가능 | DB/API가 추가 처리량을 감당하는지 확인 |

`auto.offset.reset=latest`이면 새 Partition 발견 전 기록된 메시지를 건너뛸 수 있습니다. 기존 Partition의 유효한 committed offset과 새 Partition의 초기 위치는 구분해야 합니다. [Topic 운영 문서](https://kafka.apache.org/43/operations/basic-kafka-operations/), [Consumer 설정](https://kafka.apache.org/43/configuration/consumer-configs/)

순서가 중요하면 version 기반 최신성 검사, Producer 전환 경계와 backlog drain, 고정 routing 또는 새 Topic 마이그레이션 등을 요구사항에 맞춰 설계합니다. Key 분산 방식은 Producer partitioner와 설정에 따라 달라집니다.

### Rebalancing 중 처리와 commit

할당 반환 시에는 완료된 처리 범위를 확인하고 미완료 작업 이후로 offset을 앞당겨 commit하지 않습니다. 별도 worker 작업은 소유권 변경 후에도 실행될 수 있으므로 늦은 반영과 commit을 제어합니다. 재실행되는 작업에는 [멱등 처리](kafka-offset-replay.md)가 필요합니다.

반복 Rebalancing은 처리 시간이 `max.poll.interval.ms`를 넘는지, 멤버 재시작이나 heartbeat/session·네트워크 문제인지 구분합니다. 설정만 늘리기보다 배치 크기, 처리 방식과 downstream 지연을 함께 점검합니다. 프로토콜별 지원 설정은 [Consumer 설정](https://kafka.apache.org/43/configuration/consumer-configs/)을 확인합니다.

## 예상 꼬리 질문과 답변

**Q1. Consumer와 Partition은 반드시 1:1인가요?** 아닙니다. 하나가 여러 Partition을 맡을 수 있습니다. 단일 Topic을 소비하는 같은 Group에서 Consumer 수가 Partition 수보다 많으면 일부는 할당을 받지 못합니다. 여러 Topic 구독이면 전체 할당과 assignor도 고려합니다.

**Q2. Partition 수가 많을수록 좋은가요?** 병렬성은 늘 수 있지만 로그·복제·메타데이터와 장애 복구 비용도 증가합니다. 초기 값을 무조건 작거나 크게 잡기보다 Peak, 확장 여유와 클러스터 한도로 결정합니다.

**Q3. 기존 Partition을 줄여야 한다면요?** 직접 축소 대신 새 Topic과 데이터/Producer/Consumer 전환을 설계합니다. offset, 중복 처리와 순서의 전환 경계를 검증해야 합니다.

**Q4. 증설하면 기존 Hot Key도 분산되나요?** 같은 Key를 하나의 Partition으로 보내면 그 Key 자체는 분산되지 않습니다. Key를 나누면 순서와 집계 설계가 바뀝니다. 기존 backlog도 자동 이동하지 않습니다.

**Q5. Rebalancing 뒤 Lag이 늘면 offset을 reset하나요?** 정상 할당 변경에는 필요하지 않습니다. 처리량과 반복 Rebalancing 원인을 먼저 확인합니다. 누락이 검증된 경우에만 [Replay](kafka-offset-replay.md)의 범위와 부작용을 평가합니다.

**Q6. Schema Registry 오류도 Rebalancing 문제인가요?** 별개입니다. 할당 변경은 소비 소유권 문제이고 [Schema 호환성](kafka-schema-registry.md)은 읽기 계약 문제입니다. 오류 위치로 구분해야 합니다.

## 한계 / 주의점 및 답변 보완

Partition 내부 저장 순서가 애플리케이션의 처리 완료 순서까지 보장하지는 않습니다. worker 병렬 처리, retry topic과 다른 Partition/Topic은 별도 순서 정책이 필요합니다. offset 증가도 업무 반영 완료의 증거가 아니므로 결과 데이터를 함께 평가합니다.

이 문서의 용량 모델과 사례는 설명용이며 실제 클러스터 증설·부하 시험 결과가 아닙니다.

## 관련 문서 / 공식 참고 자료

- [Offset Replay와 멱등성](kafka-offset-replay.md), [Schema Registry 호환성](kafka-schema-registry.md)
- [Apache Kafka 개요](https://kafka.apache.org/43/getting-started/introduction/)
- [Topic/Group 운영](https://kafka.apache.org/43/operations/basic-kafka-operations/), [Consumer 설정](https://kafka.apache.org/43/configuration/consumer-configs/)
- [Consumer Rebalance Protocol](https://kafka.apache.org/43/operations/consumer-rebalance-protocol/)
