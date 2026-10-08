# Cache Avalanche

## 질문 의도

“Redis 장애나 캐시 대량 만료로 DB 요청이 급증하면 어떻게 대응하겠습니까?”

개별 Key 최적화와 시스템 전체의 용량 보호를 구분하고, 복구 중에도 원본의 처리 예산을 관리할 수 있는지 평가합니다.

## 핵심 개념

이 문서에서 Cache Avalanche는 다수 Key가 동시에 miss 상태가 되어 원본에 부하가 집중되는 현상입니다. 동시 TTL 만료, 캐시 계층 장애, 대량 eviction/유실, 배포 후 cold start가 계기가 될 수 있습니다. Redis 오류와 정상적인 cache miss는 구분해서 관측해야 합니다.

| 구분 | [Stampede](cache-stampede.md) | Avalanche |
|---|---|---|
| 주요 범위 | 같은 Key의 중복 재생성 | 다수 Key의 원본 조회 집중 |
| 우선 대응 | Key별 [Single Flight](single-flight.md), 갱신 제어 | 원본 예산, admission control, 점진 Warm-up |
| TTL jitter | Hot Key 중복을 직접 억제하지 않음 | 여러 Key의 동시 만료를 분산 |

두 현상은 함께 발생할 수 있습니다. 서로 다른 10만 Key의 miss는 Single Flight만으로 합칠 수 없습니다.

## 면접 답변 예시

> Cache Avalanche는 다수 Key의 miss가 겹쳐 원본 부하가 집중되는 현상입니다. 대응의 우선순위는 원본 용량 보호입니다. 유효한 로컬 캐시와 동일 Key 요청 병합을 활용하고, 서로 다른 Key의 조회는 동시성 제한과 제한된 대기 또는 빠른 거절로 제어합니다. 캐시 의존성 장애에는 timeout과 [Circuit Breaker](../resilience/circuit-breaker.md)가 반복 대기를 줄일 수 있지만 원본 fallback 부하는 별도로 제한해야 합니다. 복구 시에는 hot set부터 낮은 우선순위로 Warm-up하며 사용자 요청과 합산한 원본 예산을 지킵니다. 연결 성공뿐 아니라 캐시 적중률, 원본 지연과 풀 대기, API 오류율을 확인하며 점진적으로 회복합니다.

## 실무 적용과 설계 판단 기준

배치가 많은 상품 Key에 동일 TTL을 설정했다면 만료 시점에 jitter를 주고 적재 시작 시점도 분산합니다. 캐시 장애에는 jitter가 효과가 없으므로 원본 부하 제어를 별도로 설계합니다.

[장애 복구](../resilience/redis-recovery.md) 평가는 “캐시 연결이 복구됐다”와 “사용자 요청이 정상화됐다”의 시점을 구분합니다. 실제 데이터 유실 여부는 영속성, 복제와 failover 구성으로 확인합니다. Redis 재시작을 전량 유실과 동일시하지 않습니다.

## 예상 꼬리 질문과 답변

**인스턴스별 semaphore만 두면 DB가 안전한가요?** 전체 한도는 인스턴스 수 × 로컬 한도로 늘어납니다. autoscaling, 배치, Warm-up을 포함해 전역 원본 예산을 배분해야 합니다.

**Warm-up을 모두 병렬 실행하면 더 빨리 복구되지 않나요?** foreground와 경쟁해 DB를 더 느리게 할 수 있습니다. 낮은 동시성/처리율, 우선순위, 중단 조건과 작업 분담이 필요합니다.

**Circuit Breaker만으로 해결되나요?** 장애 의존성의 반복 호출을 줄이지만 정상 응답 중인 DB가 과부하되는 상황을 완전히 막지 못합니다. 동시성 제한과 admission control을 병행합니다.

**부하 한도는 어떻게 정하나요?** 처리율과 동시성은 다릅니다. 평균적으로 동시 작업 수는 처리율 × 체류 시간에 비례하므로 원본 지연이 늘면 같은 QPS에서도 동시성이 커집니다. 실측한 지속 처리 용량, 지연 분포와 SLO로 검증하고 안전 여유를 둡니다.

## 한계 / 주의점 및 답변 보완

무제한 DB fallback과 무제한 대기 큐는 장애를 원본과 애플리케이션으로 전파합니다. “TTL을 랜덤하게 설정한다”는 예방 답변만으로 캐시 계층 장애를 설명할 수 없습니다. cache hit ratio 단독으로 복구를 판단하지 말고 API tail latency와 원본 포화 지표를 함께 봅니다.

설계 평가에는 데이터 유실 범위, 부하 제한 방식과 회복 판단 기준이 필요합니다. 임의의 permit 수나 hit ratio를 보편적 정답처럼 제시하지 않습니다.

## 관련 문서 / 참고 자료

- [Circuit Breaker와 장애 감지·Slack 알림](../resilience/circuit-breaker.md): 정상 miss와 장애의 구분, 차단 후 원본 보호와 운영 알림
- [Cache Stampede](cache-stampede.md), [Redis 장애 복구와 Graceful Degradation](../resilience/redis-recovery.md)
- [Redis persistence 공식 문서](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/): 복구와 데이터 보존

Avalanche의 용어 범위와 운영 정책은 본 저장소의 설명 기준이며 단일 공식 표준 정의가 아닙니다.
