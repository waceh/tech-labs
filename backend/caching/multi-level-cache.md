# Caffeine / Redis와 다단계 캐시

## 질문 의도

“Redis가 있는데 왜 Caffeine도 사용하나요? L1과 L2의 일관성은 어떻게 유지하나요?”

라이브러리 선택보다 지연, 원본 부하, 메모리와 정합성의 Trade-off를 판단하는지 평가합니다.

## 핵심 개념

Caffeine은 JVM 내부 로컬 캐시 라이브러리입니다. Redis는 네트워크로 접근하는 공유 저장소이며 캐시로 활용할 수 있습니다. Caffeine을 L1, Redis를 L2로 사용하는 것은 가능한 설계이지 두 제품의 고정된 역할이 아닙니다.

| 계층 | 역할 | 비용 / 한계 |
|---|---|---|
| Caffeine L1 | Hot data를 네트워크 없이 재사용 | 인스턴스별 중복 메모리, 값 차이, 재시작 시 cold |
| Redis L2 | 여러 인스턴스에서 캐시 값 공유 | 네트워크/직렬화 비용, timeout, 장애와 eviction |
| 원본 DB/API | 데이터 기준과 miss 시 로딩 | 제한된 처리 용량과 연결 풀 |

Cache-aside의 기본 흐름은 L1 → L2 → 원본이며, 하위 계층에서 읽은 값을 상위 캐시에 저장합니다. L2를 지운다고 모든 L1이 자동으로 무효화되지는 않습니다.

## 면접 답변 예시

> L1은 자주 조회되는 데이터를 인스턴스 안에서 재사용해 지연과 Redis 호출을 줄이고, L2는 인스턴스 간 캐시를 공유해 원본 접근을 줄입니다. 다만 L1을 추가하면 메모리 중복과 무효화 문제가 생기므로 실제 hit ratio와 허용 freshness를 보고 선택하겠습니다. 쓰기 후 L2만 삭제해도 L1에는 이전 값이 남을 수 있어 이벤트 무효화와 TTL, 필요하면 version 기반 조건부 게시를 검토하겠습니다. Redis 장애 중에는 유효한 L1을 활용하되 DB fallback을 무제한 허용하지 않고 원본 예산을 제한하겠습니다. 재고·가격·권한은 상품 설명과 동일한 stale 정책을 적용하지 않겠습니다.

## 실무 적용과 경력 연결

상품 설명처럼 읽기가 많고 일정 지연을 허용할 수 있는 데이터를 먼저 검토합니다. L1 크기는 전체 상품 수가 아니라 hot set과 인스턴스 메모리 예산에 맞춥니다. 객체 크기와 GC 비용, 인스턴스 수 증가에 따른 중복 메모리도 고려합니다.

L2 hit를 L1에 게시할 때 L1 TTL을 매번 새로 시작하면 원본 freshness 한도를 넘길 수 있습니다. 원본 갱신 시각/version과 최대 허용 age를 보존하고 필요한 경우 L2의 잔여 TTL보다 길게 유지하지 않습니다. TTL은 freshness 제어 수단이지만 강한 정합성 보장은 아닙니다.

RabbitMQ OES와 Kafka 경험은 무효화 이벤트의 중복, 지연, 순서 역전과 재처리를 설명하는 데 연결할 수 있습니다. 실제 해당 시스템이 캐시 무효화를 수행했는지는 별도로 확인하고, 적용 가능한 설계와 실제 경험을 구분합니다.

## 예상 꼬리 질문과 답변

**DB 갱신 후 캐시를 삭제하면 충분한가요?** 이미 v1을 읽기 시작한 요청이 DB v2 갱신/무효화 이후 v1을 다시 게시할 수 있습니다. version/세대 번호를 통한 조건부 게시를 검토합니다. 비교 기준도 삭제해 버리면 늦은 값을 막지 못하므로 세대 정보 보존이 필요합니다.

**이벤트 무효화면 정합성이 보장되나요?** 이벤트 지연과 누락, 중복/역전을 처리해야 합니다. DB commit과 발행의 간극에는 outbox 등을 검토하고, version 기반 멱등 처리와 TTL/보정 작업을 둡니다. 즉시 강한 정합성이 필요한 읽기는 원본 검증 등 별도 경로가 필요합니다.

**Redis 쓰기 실패는 요청 실패로 처리하나요?** 재생성 가능한 캐시라면 원본 조회 결과를 응답하고 L1을 활용할 수 있습니다. 실패 관측과 제한된 재시도 정책을 두며 캐시 쓰기 실패 때문에 성공한 원본 작업을 무제한 반복하지 않습니다. Redis가 데이터 기준인 경우에는 같은 판단을 적용할 수 없습니다.

**Single Flight는 어디에 배치하나요?** L1 miss 이후 L2 앞에 두면 Redis 호출도 합칠 수 있습니다. L2 miss 이후에 두면 원본만 합칩니다. 병목, deadline과 공유 결과 범위를 보고 결정합니다.

**Caffeine 만료 값으로 stale 응답을 해도 되나요?** 일반 만료 설정만으로 stale 값이 제공되지는 않습니다. refresh 기능이나 별도 soft/hard TTL 구조의 의미를 확인하고 최대 stale age와 실패 시 행동을 명시해야 합니다.

## 한계 / 주의점 및 답변 보완

L1/L2를 추가할수록 좋은 것은 아닙니다. 읽기 비용 절감이 작고 갱신이 빈번하거나 정합성이 엄격하면 운영 복잡도가 이익보다 클 수 있습니다. 두 계층의 hit/miss, 원본 조회량, API P99와 메모리를 측정합니다. metric label에 무제한 상품 ID를 넣으면 cardinality가 커집니다.

“Redis가 공유하므로 모두 같은 값입니다”라는 답변은 L1의 독립성을 놓칩니다. 면접에서는 무효화 전파, 이벤트 실패와 늦은 쓰기까지 설명하고 실제 적용한 freshness 기준을 제시합니다.

## 관련 문서 / 공식 참고 자료

- [Caffeine Population](https://github.com/ben-manes/caffeine/wiki/Population): 원자적/비동기 로딩
- [Caffeine Refresh](https://github.com/ben-manes/caffeine/wiki/Refresh): refresh와 expiration의 차이
- [Redis client-side caching](https://redis.io/docs/latest/develop/clients/client-side-caching/): Redis의 추적/무효화 기능. 일반 Caffeine과 자동 연동된다는 뜻은 아닙니다.
- [Single Flight](single-flight.md), [Redis 장애 복구](../resilience/redis-recovery.md)

version/이벤트 정책은 서비스의 freshness 요구사항에 따른 설계 제안입니다.
