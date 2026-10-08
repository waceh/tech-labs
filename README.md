# Tech Labs

시니어 백엔드 기술 면접에서 학습한 개념과 답변을 **기술별 독립 Markdown 문서**로 축적합니다. Backend, Architecture, Platform, AI Engineering으로 학습 범위를 확장합니다.

핵심은 개념을 설명하고, 실무 제약에 맞는 설계 판단과 Trade-off를 답변하는 것입니다. 코드와 테스트는 이해를 돕는 선택적 참고 자료입니다.

## 기술 면접 학습 목차

| 분야 | 문서 | 핵심 질문 |
|---|---|---|
| Backend / Caching | [Cache Stampede](backend/caching/cache-stampede.md) | Hot Key 만료 시 원본 부하를 어떻게 줄이는가? |
| Backend / Caching | [Cache Avalanche](backend/caching/cache-avalanche.md) | 다수 Key의 miss가 동시에 발생하면 어떻게 보호하는가? |
| Backend / Caching | [Single Flight](backend/caching/single-flight.md) | 진행 중인 동일 작업을 어떻게 공유하며 범위는 어디까지인가? |
| Backend / Caching | [Caffeine / Redis와 다단계 캐시](backend/caching/multi-level-cache.md) | L1/L2의 역할과 정합성을 어떻게 설계하는가? |
| Backend / Resilience | [Redis 장애 복구와 Graceful Degradation](backend/resilience/redis-recovery.md) | 캐시 장애 중 원본을 보호하고 서비스를 어떻게 회복하는가? |
| Backend / Resilience | [Circuit Breaker와 장애 감지·Slack 알림](backend/resilience/circuit-breaker.md) | 장애를 어떻게 감지하고 서킷 발동 이후 요청 처리와 운영 알림을 연결하는가? |
| Backend / Database | [DynamoDB와 데이터베이스 선택](backend/database/dynamodb.md) | 접근 패턴과 Key 설계로 DynamoDB, MongoDB, DocumentDB를 어떻게 비교하는가? |
| Backend / Messaging | [Kafka Partition 수와 Rebalancing](backend/messaging/kafka-partition-rebalancing.md) | 처리량에 맞는 Partition 수와 증설·할당 변경의 영향을 어떻게 판단하는가? |
| Backend / Messaging | [Kafka Offset Replay와 멱등성](backend/messaging/kafka-offset-replay.md) | 재처리 범위와 중복·이벤트 순서를 어떻게 제어하는가? |
| Backend / Messaging | [Kafka Schema Registry와 호환성](backend/messaging/kafka-schema-registry.md) | BACKWARD 정책, 배포 순서와 Schema 오류 복구를 어떻게 설명하는가? |
| Backend / Networking | [Load Balancer와 AWS 종류](backend/networking/load-balancer.md) | 연결·요청·자원 부하를 구분하고 분산 알고리즘과 Health Check를 어떻게 선택하는가? |
| Backend / Networking | [Route 53과 DNS 라우팅](backend/networking/route53-dns-routing.md) | DNS 분산과 ALB의 차이, 가중치와 장애 전환 지연을 어떻게 설명하는가? |

## 문서 작성 기준

기술별로 다음 순서로 기록합니다.

1. 질문 의도
2. 핵심 개념
3. 면접 답변 예시
4. 실무 적용과 설계 판단 기준
5. 예상 꼬리 질문과 답변
6. 한계 / 주의점 및 답변 보완
7. 관련 문서 / 공식 참고 자료

답변은 개인 경력이나 보유 기술을 전제로 하지 않고 개념, 동작 원리, 적용 조건과 한계를 객관적으로 설명합니다. 일반적인 설계 원칙과 특정 제품의 구현 예시를 구분하고, 설정 수치는 트래픽·용량·SLO에 따라 달라지는 예시로 제시합니다. 장애 원인과 개선 효과는 검증 근거가 있을 때만 사실로 기술합니다.

## 새로운 기술 추가

한 주제당 하나의 `.md` 파일을 추가하고 위 목차에 연결합니다. Backend의 concurrency/database/messaging, Architecture의 distributed-systems/system-design, Platform, AI Engineering 등은 **첫 문서를 작성할 때** 필요한 디렉터리를 만듭니다. 분야별 독립 문서를 유지하고 관련 주제는 링크로 연결합니다. 다른 문서가 있는 기술은 본문에서 처음 설명하거나 대응 방안으로 제시하는 위치에 상대 경로 링크를 둡니다. 문서 하단의 관련 자료에도 연결하되, 같은 문단에서 반복해서 링크하지 않습니다. 빈 폴더나 실행 프로젝트를 먼저 만들 필요는 없습니다.

## 선택적 코드 참고

[Java 21 Single Flight 예제](examples/cache-stampede/README.md)는 동시 요청의 중복 원본 조회를 이해하기 위한 보조 자료입니다. 면접 문서를 읽는 데 실행은 필요하지 않습니다. 기존 검증 기록은 예제에 한정하며 실제 DB/Redis 성능이나 운영 장애 복구를 검증한 결과가 아닙니다.
