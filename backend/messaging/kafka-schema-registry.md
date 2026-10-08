# Kafka Schema Registry와 호환성

## 질문 의도

“BACKWARD Compatibility는 무엇인가요? Schema 변경과 장애 복구 순서는 어떻게 결정하나요?”

읽기/쓰기 Schema의 관계와 배포 순서를 설명하고 등록 실패·읽기 실패·Rebalancing을 구분할 수 있는지 평가합니다. 정책 이름은 Confluent Schema Registry 기준이며 Kafka 자체의 필수 기능은 아닙니다.

## 핵심 개념

Schema Registry는 Schema 버전과 데이터 계약을 관리하고 호환성을 검사합니다. 검사는 Subject 단위이며 naming strategy에 따라 Topic과 Subject의 관계가 달라집니다.

| 정책 | 호환성 방향 | 배포 판단 |
|---|---|---|
| BACKWARD | 새 reader로 이전 writer 데이터를 읽을 수 있음 | Consumer 먼저 갱신해 구 데이터 읽기 검증 |
| FORWARD | 이전 reader로 새 writer 데이터를 읽을 수 있음 | Producer 먼저 갱신 가능 여부 검증 |
| FULL | 양방향 | 상호 읽기 검증, 업무 의미 변화는 별도 확인 |
| *_TRANSITIVE | 해당 방향을 모든 이전 버전과 검사 | 장기 replay와 여러 배포 버전 고려 |

기본 BACKWARD는 직전 버전과 검사하며 BACKWARD_TRANSITIVE는 모든 이전 버전과 검사합니다. 허용 변경은 Avro·Protobuf·JSON Schema별로 다릅니다. [Confluent Schema Evolution](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html)

### Avro 필드 추가 예시

기존 record가 `id`만 포함할 때 다음처럼 reader에 기본값이 있는 필드를 추가하면 이전 데이터에서 누락된 필드를 해석할 수 있습니다.

```json
{
  "type": "record",
  "name": "EntityEvent",
  "fields": [
    {"name": "id", "type": "string"},
    {"name": "source", "type": "string", "default": "unknown"}
  ]
}
```

이는 **Avro 예시**입니다. Default는 Schema resolution에서 누락된 reader 필드를 채우는 규칙이며 과거 bytes를 변경하지 않습니다. 형식별 타입·이름·기본값 규칙을 함께 확인합니다. [Avro Schema resolution](https://avro.apache.org/docs/1.12.0/specification/#schema-resolution)

## 면접 답변 예시

> BACKWARD는 새 reader Schema가 이전 writer Schema로 기록된 데이터를 읽을 수 있는지 검사하는 정책입니다. 기본 정책은 직전 버전과 검사하며 장기 replay에는 BACKWARD_TRANSITIVE를 검토합니다.

추가 설명: Avro의 기본값 있는 필드 추가는 예시이고 다른 형식은 규칙이 다릅니다. 일반적인 BACKWARD 변경은 Consumer부터 갱신해 기존 데이터 읽기를 확인한 뒤 Producer를 전환합니다. Schema 오류와 Rebalancing은 별개이며 등록 실패인지 기록된 데이터의 읽기 실패인지 구분해야 합니다. 새 버전 등록은 기존 메시지를 수정하지 않으므로 수정된 Consumer와 실제 데이터로 재처리를 검증합니다.

## 실무 적용과 설계 판단 기준

1. Subject와 형식, 호환성 정책, 현재 Producer/Consumer 버전을 확인합니다.
2. 등록 전 compatibility 검사와 계약 테스트를 수행합니다. 읽기 가능성과 업무 의미의 호환성은 별개입니다.
3. BACKWARD 변경에서는 Consumer가 이전·새 데이터를 모두 처리하는지 확인한 뒤 Producer를 전환합니다. 구 Consumer로 rollback할 때 새 데이터 읽기도 검증합니다.
4. [Offset Replay](kafka-offset-replay.md)는 실제 writer Schema와 ID로 과거 데이터를 읽어 봅니다. 직전 버전 호환성만으로 장기 이력을 가정하지 않습니다.
5. 의도적 비호환 변경은 새 Subject/Topic이나 변환 경로를 설계합니다. 정책을 NONE으로 낮추는 것만으로 기존 Consumer가 안전해지지 않습니다.

| 오류 위치 | 복구 판단 |
|---|---|
| 등록 단계 호환성 실패 | 호환되게 수정하거나 별도 계약으로 분리. 실패한 등록만으로 replay 필요성을 단정하지 않음 |
| Producer 직렬화 실패 | 발행 실패 여부, 재시도와 원본 데이터 보존 확인 |
| Consumer 역직렬화 실패 | writer ID, Registry 접근과 reader 문제 구분. 읽기 가능한 Consumer/변환기 마련 |
| 읽기는 되지만 잘못된 업무 반영 | 코드/계약 수정 후 영향 범위 검증과 재처리 |
| Registry 접근 장애 | 인증·네트워크·캐시된 Schema 사용 여부 확인. 호환성 정책 변경으로 해결하지 않음 |

새 등록은 기존 Schema ID의 메시지를 새 데이터로 바꾸지 않습니다. 과거 데이터에는 호환 reader, 변환 또는 원본 재발행이 필요할 수 있습니다. replay만 반복하면 같은 위치에서 실패합니다. [Confluent SerDes 동작](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/index.html)

## 예상 꼬리 질문과 답변

**BACKWARD면 모든 과거 메시지를 읽을 수 있나요?** 기본 검사는 직전 버전 대상입니다. 모든 이전 버전은 TRANSITIVE 정책으로 검사하고 실제 replay 데이터로도 검증합니다.

**Default만 있으면 필드 추가가 항상 안전한가요?** Avro의 해당 예시이며 형식과 다른 변경을 함께 검사해야 합니다. 업무 필수값을 `unknown`으로 해석해도 되는지와 Consumer 로직은 별도 문제입니다.

**구 Consumer로 rollback할 수 있나요?** 새 writer를 구 reader가 읽는 방향은 BACKWARD만으로 보장되지 않습니다. 양방향 호환성이나 별도 전환 전략을 검증합니다.

**브로커가 모든 payload를 검증하나요?** Registry의 등록 검사, 클라이언트 SerDes 사용과 브로커 측 검증 설정은 다릅니다. 실제 발행 경로의 검증 범위를 확인합니다.

**오류 해결 후 반드시 offset을 되돌리나요?** 정상 [Rebalancing](kafka-partition-rebalancing.md)에는 필요하지 않습니다. 실패 위치에 머물렀다면 재시도로 처리할 수 있고 이미 건너뛰었거나 잘못 반영한 범위가 있으면 [Replay와 멱등성](kafka-offset-replay.md)을 평가합니다.

## 한계 / 주의점 및 답변 보완

호환성 검사는 구조의 읽기 가능성 검사입니다. 금액 단위나 enum의 업무 의미 변화까지 보장하지 않습니다. 과거 Schema 삭제·접근 불가도 replay를 막을 수 있어 Schema 보존·권한을 데이터 보존 정책에 포함합니다.

JSON은 설명용이며 실제 Registry 등록·호환성 API 호출·재처리 검증 결과가 아닙니다.

## 관련 문서 / 공식 참고 자료

자료 확인일: 2026-10-08. Kafka 동작은 Apache Kafka 4.3 문서 기준입니다. Schema Registry는 Confluent current, Avro 예시는 1.12.0 기준입니다.

- [Partition과 Rebalancing](kafka-partition-rebalancing.md), [Offset Replay와 멱등성](kafka-offset-replay.md)
- [Confluent Schema Evolution](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html), [Confluent SerDes](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/index.html)
- [Avro Schema resolution](https://avro.apache.org/docs/1.12.0/specification/#schema-resolution)
