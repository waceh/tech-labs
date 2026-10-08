# Cache Stampede

## 질문 의도

“인기 상품 캐시가 만료되는 순간 요청이 몰리면 어떻게 대응하겠습니까?”

캐시 hit 시의 성능보다 miss 시 원본 부하와 장애 전파를 이해하는지, 대응 수단의 범위와 정합성 비용까지 설명하는지 평가합니다.

## 핵심 개념

Cache Stampede는 같은 Key의 캐시가 없거나 만료될 때 동시 요청이 각자 원본 DB/API를 조회하고 값을 재생성하여 부하를 증폭시키는 현상입니다. TTL 만료 외에도 무효화, eviction, cold start로 발생합니다. 원본이 느려지면 중첩 요청이 늘고, 그 부하가 원본을 더 느리게 만드는 악순환이 생깁니다.

이 문서에서는 **동일 Key 중복 재생성**을 Stampede, **다수 Key miss에 따른 원본 부하 집중**을 [Avalanche](cache-avalanche.md)로 구분합니다. 용어 사용은 자료마다 다르며 함께 발생할 수 있습니다.

- [Single Flight](single-flight.md): 같은 Key의 진행 중인 조회를 공유합니다.
- Early refresh: 만료 전에 갱신해 miss 구간을 줄입니다. 갱신 자체에도 중복 억제가 필요합니다.
- Stale-while-revalidate: 허용된 오래된 값을 응답하면서 갱신합니다. 최대 stale age와 갱신 실패 정책이 필요합니다.
- TTL jitter: 여러 Key의 만료 시점을 분산합니다. 한 Hot Key의 동시 요청을 직접 합치지는 않습니다.

## 면접 답변 예시

> Cache Stampede는 동일 Key의 캐시가 만료되거나 없을 때 중복 원본 조회가 겹쳐 부하를 증폭시키는 현상입니다. Single Flight는 진행 중인 동일 Key 조회를 공유하며, leader의 캐시 재확인으로 불필요한 원본 조회를 줄일 수 있습니다. 로컬 제어는 인스턴스 간 중복을 막지 못하고 서로 다른 Key의 부하도 합치지 못하므로 원본 동시성 제한과 admission control을 함께 고려해야 합니다. 오래된 값이 허용되는 데이터는 최대 stale age를 둔 응답과 비동기 갱신을 사용할 수 있고, 재고나 권한처럼 정합성이 중요한 데이터는 별도 검증이 필요합니다. 효과는 원본 조회 수, 대기 요청 수, tail latency와 오류율로 평가합니다.

## 실무 적용과 설계 판단 기준

인기 콘텐츠나 상품 상세처럼 특정 Key에 읽기가 집중되는 경로가 적용 예시입니다. 설계할 때 Key별 트래픽 집중도, TTL과 eviction 원인, 원본 지연, 인스턴스 수를 먼저 확인합니다. 전체 hit ratio가 높아도 특정 Hot Key의 만료 구간에서 장애가 발생할 수 있으므로 원본 QPS와 동시성의 순간 증가를 함께 봅니다.

대응 방식은 [캐시 계층](multi-level-cache.md), 정상 miss와 timeout의 비율, 원본 용량, 데이터의 허용 freshness를 기준으로 선택합니다. 동일 Key 중복 억제와 전체 원본 부하 제한은 각각 검증해야 합니다.

## 예상 꼬리 질문과 답변

**TTL을 늘리면 해결되나요?** 만료 빈도는 줄지만 다음 만료 시점의 중복 조회는 남고 freshness가 나빠집니다. TTL은 업무의 허용 지연에 맞추고 miss 제어를 별도로 둡니다.

**인스턴스가 10개라면 DB를 한 번만 조회하나요?** 로컬 coordinator는 공유되지 않습니다. 하나의 중첩 작업 구간에서도 각 인스턴스에서 조회할 수 있습니다. 전역 중복 억제의 필요성과 락 비용을 비교합니다.

**원본이 계속 실패하면요?** 현재 follower에게 실패를 공유해도 이후 요청이 재시도 leader가 됩니다. timeout, [Circuit Breaker](../resilience/circuit-breaker.md), backoff/jitter, retry budget과 원본 예산이 필요합니다.

**없는 상품도 캐시하나요?** 안전한 not-found 결과는 짧은 negative caching을 검토할 수 있습니다. 일시적 원본 오류를 not-found로 저장하면 안 됩니다. 권한별 결과와 생성 직후의 가시성도 고려합니다.

## 한계 / 주의점 및 답변 보완

“락을 걸면 해결됩니다”만으로는 부족합니다. 락 범위, 미획득 요청의 행동, deadline, 실패 후 재시도와 정합성을 설명해야 합니다. 대기 follower와 in-flight Key 수도 메모리를 쓰므로 제한해야 합니다. Single Flight는 읽기 중복 억제이며 쓰기/무효화와의 정합성을 보장하지 않습니다.

답변에 실제 Key 분포, 원본 용량, 허용 stale age와 관측 결과를 추가하면 판단 근거가 명확해집니다. 모의 조회 100→1을 운영 성능 개선 수치로 사용하지 않습니다.

## 관련 문서 / 참고 자료

- [Single Flight](single-flight.md), [다단계 캐시](multi-level-cache.md)
- [Redis 장애 복구](../resilience/redis-recovery.md)
- [Go singleflight 공식 문서](https://pkg.go.dev/golang.org/x/sync/singleflight): 동일 Key 호출 중복 억제

Stampede/Avalanche 구분과 대응 조합은 현상을 설명하기 위한 설계 관점입니다.
