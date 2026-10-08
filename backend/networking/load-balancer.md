# Load Balancer: 분산 기준, AWS 종류와 Health Check

## 질문 의도

“LB는 부하가 적은 서버를 어떻게 판단하나요? CPU를 보고 선택하나요?”

트래픽 분산 알고리즘, 대상의 정상 여부, 자원 모니터링과 자동 확장을 구분할 수 있는지 평가합니다. 제품별 지원 기능과 기본값도 구분해야 합니다.

## 핵심 개념

LB가 기본적으로 CPU나 메모리 사용률을 조회해 가장 여유로운 서버를 고르는 것은 아닙니다. 선택 기준은 **설정된 알고리즘이 관측하거나 입력받는 정보**입니다. 요청 수가 같아도 작업 비용이 다르면 실제 CPU 사용률은 다를 수 있습니다.

### Q1. 어떤 기준으로 트래픽을 분산하나요?

| 일반적인 알고리즘 | 선택 기준 | 주의점 |
|---|---|---|
| Round Robin | 대상을 순차적으로 선택 | 같은 요청 수가 같은 작업량을 뜻하지 않음 |
| Weighted Round Robin | 설정한 가중치에 비례해 순차 분배 | 가중치가 CPU 관측으로 자동 결정된다는 뜻은 아님 |
| Least Connections | LB가 추적하는 활성 연결 수 | 연결마다 요청량·유지 시간이 다를 수 있음 |
| Least Outstanding Requests | 아직 완료되지 않은 요청 수 | 요청별 비용·오류 원인을 직접 알지는 못함 |
| IP Hash | 클라이언트 IP의 해시 | NAT 뒤 여러 사용자가 같은 대상으로 집중할 수 있음 |

위 표는 알고리즘의 일반 개념입니다. 모든 LB가 전부 지원하는 것은 아니며 동률 처리, 가중치와 관측 범위도 구현별로 다릅니다. Round Robin·가중치·Least Connections·IP Hash의 구현 예시는 [NGINX upstream 문서](https://nginx.org/en/docs/http/ngx_http_upstream_module.html)를 참고할 수 있습니다. 분산 LB의 각 노드가 보는 상태를 전체 시스템의 완전한 실시간 상태로 가정하지 않습니다.

예를 들어 A에는 미완료 요청 2개, B에는 20개가 있다면 Least Outstanding Requests는 A를 선호합니다. 이는 “A의 CPU가 더 낮다”는 결론이 아니라 “LB가 추적하는 미완료 요청이 더 적다”는 판단입니다. B의 응답이 느리면 요청이 오래 남기 때문에 간접적으로 적은 트래픽을 받게 될 수 있습니다.

### Q2. AWS의 Load Balancer 종류는 무엇인가요?

Elastic Load Balancing(ELB)은 AWS의 서비스군 이름이며 ALB·NLB·GWLB 등의 제품이 있습니다. Route 53은 [DNS 서비스](route53-dns-routing.md)로 별도 역할입니다.

| 종류 | 주로 동작하는 계층 | 주요 용도 |
|---|---|---|
| ALB | L7 | HTTP/HTTPS, host/path 기반 라우팅, WebSocket·gRPC |
| NLB | L4 | TCP/UDP 등 연결·flow 기반 분산, 고정 IP 요구 |
| GWLB | L3 네트워크 계층 | 방화벽·검사 장비로 IP 패킷 전달, GENEVE 캡슐화 |
| Classic LB | 이전 세대 | 기존 환경 유지. 신규 설계에서는 요구에 맞는 현행 제품 검토 |

GWLB를 일반 HTTP 서버용 LB로 설명하지 않습니다. ALB 기능은 [ALB 문서](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/introduction.html), NLB와 GWLB의 범위는 [NLB](https://docs.aws.amazon.com/elasticloadbalancing/latest/network/introduction.html), [GWLB](https://docs.aws.amazon.com/elasticloadbalancing/latest/gateway/introduction.html)를 참고합니다.

ALB Target Group의 기본 알고리즘은 **Round Robin**입니다. Least Outstanding Requests와 Weighted Random도 지원합니다. Weighted Random은 Weighted Round Robin과 다르며 ATW anomaly mitigation을 사용할 수 있습니다. 알고리즘·stickiness·slow start 조합에는 제약이 있으므로 [Target Group 속성](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html)을 확인합니다.

NLB는 새 TCP 연결을 flow hash로 Target에 배정하고 해당 연결을 같은 Target에 유지합니다. HTTP 요청마다 Least Connections로 다시 고르는 것으로 해석하면 안 됩니다. [ELB 동작 방식](https://docs.aws.amazon.com/elasticloadbalancing/latest/userguide/how-elastic-load-balancing-works.html)

### Q3. LB가 관측할 수 있는 부하 신호는 무엇인가요?

| 신호 | 의미 | 활용 범위 |
|---|---|---|
| 활성 연결 수 | 연결이 몇 개 유지되는가 | 해당 알고리즘을 지원하는 LB의 선택 근거 |
| 미완료 요청 수 | 요청이 몇 개 처리 중인가 | Least Outstanding Requests의 선택 근거 |
| 응답 시간·HTTP 오류·연결 실패 | 대상의 성능 저하와 실패 | 관측·알림 또는 제품의 별도 적응형 기능 |
| CPU·메모리·GC·내부 큐 | 서버 내부 자원과 병목 | 일반적으로 별도 모니터링/확장 정책 필요 |

수집되는 metric이 모두 기본 라우팅 알고리즘에 사용되는 것은 아닙니다. ALB ATW는 Target의 HTTP 5xx와 연결 실패 비율 차이를 관측해 이상을 감지하고, 설정된 mitigation에서 이상 Target의 트래픽을 줄입니다. CPU 기반 스케줄링과는 다릅니다. [AWS ATW 설명](https://aws.amazon.com/blogs/networking-and-content-delivery/improving-availability-with-application-load-balancer-automatic-target-weights/)

## 면접 답변 예시

> LB의 분산 기준은 설정한 알고리즘에 따라 달라집니다. Round Robin은 순차 분배하고 Least Connections는 연결 수, Least Outstanding Requests는 미완료 요청 수를 이용합니다. 이 수치는 실제 CPU나 메모리 여유와 동일하지 않습니다. AWS ALB의 기본값은 Round Robin이며 Target Group에서 다른 지원 알고리즘을 선택할 수 있습니다. Health Check는 처리 가능한 대상인지 판단하고 알고리즘은 그 대상 중 어디로 보낼지 결정합니다. 자원 부하 관측과 확장은 별도 정책입니다. Route 53은 DNS로 엔드포인트를 선택하고 ALB는 실제 HTTP 요청을 분산하므로 함께 사용할 수 있습니다.

## 실무 적용과 설계 판단 기준

### Q4. Health Check와 Load Balancing은 어떻게 다른가요?

Health Check는 구성한 경로·응답 코드·timeout·연속 성공/실패 기준으로 대상의 정상 여부를 판단합니다. Load Balancing은 라우팅 후보에서 대상을 선택합니다. CPU 95%여도 체크가 통과하면 Round Robin 대상일 수 있습니다. 반대로 가벼운 `/health` 성공만으로 실제 업무 경로가 정상이라고 보장하지 않습니다.

ALB는 일반적으로 healthy Target으로 보내지만 **모든 Target이 unhealthy이면 fail-open으로 unhealthy Target에도 보낼 수 있습니다.** Health Check 실패가 언제나 모든 요청 차단을 뜻하는 것은 아닙니다. [ALB Health Check](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/target-group-health-checks.html)

| Probe | 판단 목적 | 실패 시 대표 동작 |
|---|---|---|
| Liveness | 프로세스가 복구 불가능하게 멈췄는가 | Kubernetes의 컨테이너 재시작 |
| Readiness | 지금 트래픽을 받아 처리 가능한가 | 일반적인 Service 라우팅 대상에서 제외 |
| Startup | 초기 기동이 끝났는가 | 초기화 중 다른 probe의 조기 실패 방지 |

LB Target Health와 Kubernetes Readiness는 서로 다른 관측 경로이므로 설정과 시차를 함께 봅니다. [Kubernetes Probe](https://kubernetes.io/docs/concepts/workloads/pods/probes/)

Spring Boot는 `/actuator/health/liveness`, `/actuator/health/readiness` 그룹을 제공합니다. 외부 공유 DB의 장애를 liveness에 연결하면 전체 재시작을 유발할 수 있습니다. Readiness에 포함할지는 DB 없이 제공 가능한 기능, 트래픽 제거 효과와 공유 장애의 영향을 판단합니다. DB가 필수인데 성공을 반환하라는 뜻은 아닙니다. [Spring Boot Health Probe](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)

선택적 캐시 장애는 [Circuit Breaker와 fallback](../resilience/circuit-breaker.md), [Graceful Degradation](../resilience/redis-recovery.md)으로 처리 가능한지 평가합니다. 인스턴스 추가 시에는 [cold cache와 원본 부하](../caching/cache-avalanche.md)도 고려합니다.

### Q5. ALB와 Kubernetes Service, Pod는 어떻게 연결되나요?

AWS Load Balancer Controller는 Kubernetes 리소스를 보고 AWS LB/Target Group을 구성하는 제어 컴포넌트입니다. 요청이 Controller 프로세스를 통과하는 것은 아닙니다.

| ALB Target Type | 대표 데이터 경로 | 의미 |
|---|---|---|
| `instance` | ALB → Node의 NodePort → Service 데이터 경로 → Pod | ALB가 고르는 대상은 Node, 이후 Pod 선택은 Kubernetes 네트워크가 담당 |
| `ip` | ALB → 등록된 Pod IP:port | Service/Endpoint 정보로 Target을 구성하지만 ClusterIP를 반드시 경유하지 않음 |

`instance` 모드는 NodePort/LoadBalancer Service 조건을 확인하고 `ip` 모드는 Pod IP의 VPC 도달 가능성을 확인합니다. 실제 경로는 네트워크 구현과 `externalTrafficPolicy` 등에 따라 달라집니다. [Controller Target Type](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/ingress/annotations/), [Kubernetes Service](https://kubernetes.io/docs/concepts/services-networking/service/)

### Q6. HPA로 Pod를 늘렸는데 특정 Pod에 집중되는 이유는 무엇인가요?

HPA는 metric으로 replica 수를 조절하며 기존 연결이나 요청을 새 Pod로 재배치하는 장치는 아닙니다. [Kubernetes HPA](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/)

- 새 Pod가 Ready인지, Endpoint와 ALB Target에 등록되고 health check를 통과했는지 확인합니다.
- sticky session과 Service session affinity, IP hash 등 대상 고정 설정을 확인합니다.
- TCP/gRPC/WebSocket 등 장기 연결은 기존 대상에 남을 수 있습니다. L4 연결 분산과 L7 요청 분산을 구분합니다.
- `instance` Target이면 ALB의 Node 분산과 Node 뒤 Pod 분산을 각각 확인합니다.
- 요청 수뿐 아니라 처리 시간·bytes·CPU·큐를 비교합니다. 요청 수가 균등해도 작업 비용 편향은 남을 수 있습니다.

## 예상 꼬리 질문과 답변

**Q1. Least Connections와 Least Outstanding Requests의 차이는?** 전자는 연결 수, 후자는 미완료 요청 수입니다. 유휴 keep-alive 연결 100개보다 바쁜 연결 1개의 작업량이 클 수 있고 HTTP/2는 한 연결에 여러 stream이 존재할 수 있습니다. 단위가 다르므로 용도와 프로토콜에 맞춰 선택합니다.

**Q2. 느린 서버는 Least Outstanding Requests에서 자동으로 제외되나요?** 요청이 쌓여 덜 선택될 수 있지만 unhealthy가 되는 것은 아닙니다. 빨리 오류를 반환하는 서버는 요청 수가 적어 보일 수도 있으므로 오류 관측과 별도 보호가 필요합니다.

**Q3. Round Robin이면 각 서버가 정확히 같은 요청 수를 받나요?** 짧은 관측 구간, Target 변경, stickiness와 LB 노드별 동작 때문에 정확한 동일 건수를 보장한다고 설명하면 안 됩니다. 설계 의도와 실측 분포를 구분합니다.

**Q4. ALB Weighted Target Group은 서버별 Weighted Round Robin인가요?** 아닙니다. Listener의 forward action이 Target Group을 가중치로 고르고 이후 해당 Group의 알고리즘이 Target을 고릅니다. [Route 53 Weighted와 비교](route53-dns-routing.md)

**Q5. 종료하는 Pod의 요청은 어떻게 보호하나요?** 신규 유입 제거, Target deregistration/draining과 애플리케이션 graceful shutdown을 맞춥니다. in-flight 요청을 완료할 시간과 종료 유예를 검증해야 합니다. [ALB Deregistration delay](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html)

## 한계 / 주의점 및 답변 보완

자체 구축한 단일 LB 프로세스와 대체 진입 경로가 없다면 LB가 [SPOF](../resilience/spof-high-availability.md)일 수 있습니다. 반면 AWS 관리형 LB의 DNS 이름이 하나라는 이유로 물리 LB도 한 대라고 판단하지 않습니다. 활성 AZ의 LB 노드와 Target 배치, 장애 후 잔여 용량을 확인해야 합니다. [ELB 구조](https://docs.aws.amazon.com/elasticloadbalancing/latest/userguide/how-elastic-load-balancing-works.html)

LB는 모든 downstream 용량을 보장하지 않습니다. 서버 증설이나 더 균등한 분산이 DB/API 포화를 해결하지 못할 수 있으므로 원본 예산과 admission control도 필요합니다. 제품의 알고리즘·health·stickiness·프로토콜 설정을 확인한 뒤 판단합니다.

이 문서는 동작과 설계 기준이며 실제 AWS/Kubernetes 리소스 생성·트래픽 분산 시험 결과가 아닙니다.

## 관련 문서 / 공식 참고 자료

- [Route 53과 DNS 라우팅](route53-dns-routing.md), [Circuit Breaker](../resilience/circuit-breaker.md), [Cache Avalanche](../caching/cache-avalanche.md)
- [ELB 동작 방식](https://docs.aws.amazon.com/elasticloadbalancing/latest/userguide/how-elastic-load-balancing-works.html)
- [ALB Target Group 속성](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html), [ALB Health Check](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/target-group-health-checks.html)
- [AWS Load Balancer Controller](https://kubernetes-sigs.github.io/aws-load-balancer-controller/latest/guide/ingress/annotations/)
- [Kubernetes Service](https://kubernetes.io/docs/concepts/services-networking/service/), [HPA](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/)
