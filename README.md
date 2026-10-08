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

## 문서 작성 기준

기술별로 다음 순서로 기록합니다.

1. 질문 의도
2. 핵심 개념
3. 면접 답변 예시
4. 실무 적용과 경력 연결
5. 예상 꼬리 질문과 답변
6. 한계 / 주의점 및 답변 보완
7. 관련 문서 / 공식 참고 자료

답변 예시는 설계 설명이며 실제 수행 경력을 뜻하지 않습니다. 본인의 경험을 넣을 때는 관측 사실, 본인 역할, 선택 근거, 결과와 미검증 범위를 구분합니다. 확인되지 않은 장애 원인이나 개선 수치를 만들어 넣지 않습니다.

## 새로운 기술 추가

한 주제당 하나의 `.md` 파일을 추가하고 위 목차에 연결합니다. Backend의 concurrency/database/messaging, Architecture의 distributed-systems/system-design, Platform, AI Engineering 등은 **첫 문서를 작성할 때** 필요한 디렉터리를 만듭니다. 분야별 독립 문서를 유지하고 관련 주제는 링크로 연결합니다. 빈 폴더나 실행 프로젝트를 먼저 만들 필요는 없습니다.

## 선택적 코드 참고

[Java 21 Single Flight 예제](examples/cache-stampede/README.md)는 동시 요청의 중복 원본 조회를 이해하기 위한 보조 자료입니다. 면접 문서를 읽는 데 실행은 필요하지 않습니다. 기존 검증 기록은 예제에 한정하며 실제 DB/Redis 성능이나 운영 장애 복구를 검증한 결과가 아닙니다.
