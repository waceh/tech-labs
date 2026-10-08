# Tech Labs

백엔드 시스템, 분산 시스템, AI Engineering의 동작 원리를 구현하고 검증하는 기술 실험 저장소입니다.

각 실험은 **문제 → 개념 → 구현 → 검증 → Trade-offs** 순서로 기록합니다. 측정한 사실과 운영 설계 제안을 구분합니다.

## 실험 목록

| 주제 | 내용 | 실행 환경 |
|---|---|---|
| [Cache Stampede & Single Flight](caching/cache-stampede/README.md) | 동일 Key 중복 조회, Future 공유, Redis 복구 설계 | Java 21 / Spring Boot / Maven |

실험 코드는 학습용이며, 운영 도입 전에는 각 문서의 한계와 추가 검증 항목을 확인하세요.
