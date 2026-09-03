# IEUM

> Local Last-Call Food Reservation Service

**소상공인의 마감 상품을 지역 소비자와 연결해 음식물 폐기를 줄이는 예약 서비스**

IEUM(이음)은 동네 시장과 음식점이 영업 종료 전에 남은 신선식품과 조리식품을 등록하고, 주변 소비자가 할인된 가격으로 예약해 방문 구매할 수 있도록 연결합니다.

실제 결제 대신 **예약 기반 구매 흐름**을 구현하며, 한정된 재고에 요청이 집중되는 상황에서도 초과 예약과 중복 처리가 발생하지 않는 시스템을 만드는 것이 핵심 목표입니다.

## Project Goal

상품 재고가 100개일 때 10,000건의 예약 요청이 동시에 들어와도 다음 결과를 보장합니다.

- 예약 성공: 정확히 100건
- 최종 가용 재고: 0개
- 초과 예약: 0건
- 동일 요청의 중복 예약: 0건
- 취소·만료 시 재고 복구: 예약당 1회

높은 TPS 자체보다 **부하가 증가해도 데이터 정합성을 깨뜨리지 않는 시스템**을 지향합니다.

## Core Values

| 관점 | 목표 |
|---|---|
| 지역 상생 | 폐기 예정 상품을 지역 소비자에게 연결 |
| 환경 | 신선식품과 조리식품의 불필요한 폐기 감소 |
| 동시성 | 한정 재고의 초과 예약 방지 |
| 멱등성 | 재시도와 중복 요청에도 결과를 한 번만 반영 |
| 확장성 | 측정 결과를 근거로 Kafka와 Kubernetes 도입 |
| 관측 가능성 | Prometheus와 Grafana로 병목과 정합성 관측 |

## Main Features

### Merchant

- 가게 정보 등록 및 관리
- 마감 상품, 사진, 가격, 수량 등록
- 예약·픽업 가능 시간 설정
- 상품별 예약 현황 조회
- 픽업 코드 확인 및 수령 완료 처리
- 상품 판매 종료

### Customer

- 지역별 판매 중 상품 조회
- 상품 상세 및 남은 수량 확인
- 상품 예약 및 취소
- 내 예약 상태 조회
- 픽업 코드 확인

### Reservation System

- Redis Lua Script 기반 원자적 재고 차감
- Idempotency-Key 기반 중복 요청 방지
- 동일 사용자의 동일 상품 중복 활성 예약 방지
- TTL 기반 예약 만료
- 만료·취소 시 재고 원자적 복구
- Kafka 기반 예약 요청 대기열
- Kubernetes 다중 인스턴스 및 수평 확장

## System Invariants

재고 조정이 없는 한 상품별 수량은 항상 다음 관계를 만족해야 합니다.

~~~text
initialStock = availableStock
             + sum(RESERVED.quantity)
             + sum(COMPLETED.quantity)
~~~

추가로 다음 불변식을 보장합니다.

~~~text
availableStock >= 0

reservedQuantity + completedQuantity <= initialStock

count(reservation by userId + idempotencyKey) <= 1

count(active reservation by userId + productId) <= 1

stockRestorationCount per reservation <= 1
~~~

## Architecture Evolution

~~~mermaid
flowchart LR
    V1["V1<br/>Redis 동기 예약"] --> T["k6 부하 테스트<br/>병목 측정"]
    T --> V2["V2<br/>Kafka 예약 대기열"]
    V2 --> V3["V3<br/>Kubernetes Scale-out"]
~~~

### V1 — Redis Synchronous Reservation

~~~mermaid
flowchart LR
    C[Client] --> API[Spring Boot API]
    API --> DB[(MySQL)]
    API --> R[(Redis)]
    API --> M[Micrometer]
    M --> P[Prometheus]
    P --> G[Grafana]
~~~

API 요청 안에서 Redis Lua Script를 실행해 예약 성공 여부를 즉시 결정합니다.

하나의 원자적 연산으로 다음 과정을 처리합니다.

1. 멱등 키와 요청 본문 확인
2. 상품 상태와 예약 가능 시간 확인
3. 사용자의 활성 예약 확인
4. 가용 재고 확인
5. 재고 차감
6. 예약 생성
7. 만료 인덱스 등록
8. 멱등 처리 결과 저장

### V1.1 — Reliable Expiration

Redis Keyspace Notification은 정확한 실행 시각과 이벤트 전달을 보장하는 스케줄러가 아니므로, 예약 만료를 TTL 이벤트에만 의존하지 않습니다.

- Reservation TTL
- 만료 예정 시간을 저장하는 Redis Sorted Set
- 만료 상태 전이와 재고 복구를 수행하는 Expiry Worker
- 지연·누락된 만료를 탐지하는 Reconciliation Job
- 중복 실행에도 한 번만 재고를 복구하는 Lua Script

### V2 — Kafka Reservation Queue

~~~mermaid
flowchart LR
    C[Client] --> API[Reservation API]
    API --> K[(Kafka)]
    K --> W[Reservation Worker]
    W --> R[(Redis)]
    W --> D[(DLQ)]
~~~

Kafka를 예약 요청 대기열로 사용해 순간적으로 몰린 트래픽을 버퍼링합니다.

- API는 요청 접수 후 202 Accepted와 요청 ID 반환
- Consumer가 예약을 처리한 뒤 최종 성공 또는 거절 결정
- productId를 Message Key로 사용해 동일 상품을 같은 Partition에 배치
- At-Least-Once 전달을 전제로 Consumer 멱등성 보장
- 제한된 재시도 후 처리 불가능한 메시지는 DLQ로 이동
- Consumer Lag와 예약 E2E 지연 관측

### V3 — Kubernetes Scale-out

- Reservation API와 Worker를 별도 Deployment로 운영
- API는 HPA를 사용해 요청 부하에 따라 확장
- Worker는 Kafka Consumer Lag 기반 확장 검토
- Liveness와 Readiness Probe 분리
- Graceful Shutdown 적용
- 다중 Pod 환경에서도 동일한 예약 정합성 보장

## Reservation Status

~~~mermaid
stateDiagram-v2
    [*] --> RESERVED: 예약 성공 및 재고 차감
    RESERVED --> COMPLETED: 픽업 완료
    RESERVED --> CANCELLED: 사용자 취소 및 재고 복구
    RESERVED --> EXPIRED: 예약 만료 및 재고 복구
    COMPLETED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
~~~

COMPLETED, CANCELLED, EXPIRED는 최종 상태입니다. 같은 명령이 반복되더라도 상태와 재고를 추가로 변경하지 않습니다.

## Idempotency Policy

예약 생성 요청은 Idempotency-Key 헤더를 필수로 사용합니다.

- 같은 사용자 + 같은 키 + 같은 요청 본문: 최초 예약 결과 반환
- 같은 사용자 + 같은 키 + 다른 요청 본문: 409 IDEMPOTENCY_KEY_REUSED
- 같은 사용자가 다른 키로 같은 상품을 중복 예약: 409 ACTIVE_RESERVATION_EXISTS
- 멱등 결과 기본 보존 시간: 24시간
- Kafka 메시지가 재전달되어도 예약과 재고는 한 번만 반영

## Tech Stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.x |
| Persistence | Spring Data JPA, MySQL 8 |
| Reservation | Redis, Lua Script |
| Messaging | Apache Kafka |
| Authentication | Spring Security, JWT |
| Monitoring | Micrometer, Prometheus, Grafana |
| Test | JUnit 5, AssertJ, Testcontainers, k6 |
| Container | Docker |
| Orchestration | Kubernetes, HPA, optionally KEDA |
| Build | Gradle |
| API Documentation | OpenAPI/Swagger or Spring REST Docs |

## Monitoring

Prometheus가 애플리케이션과 인프라 지표를 수집하고 Grafana에서 다음 대시보드를 구성합니다.

- Service Overview: RPS, p95/p99, 오류율, 활성 Pod
- Reservation Correctness: 성공·거절, 초과 예약, 중복 예약, 재고 불일치
- Redis: CPU, Memory, Ops/sec, Command Latency, Expiration
- Kafka: Produce/Consume Rate, Consumer Lag, Retry, DLQ
- Kubernetes: Replica, CPU/Memory, Restart, Scaling Event
- Load Test Comparison: V1/V2/V3 처리량, 지연, 오류율, 자원 사용량

주요 사용자 ID와 예약 ID는 Metric Label로 사용하지 않고 구조화 로그를 통해 추적합니다.

## Load Test Scenarios

| Scenario | Purpose |
|---|---|
| Normal Load | 기본 처리량과 지연 기준선 측정 |
| Burst Traffic | 순간 요청 폭증과 Kafka 버퍼링 효과 확인 |
| Hot Product | 하나의 상품에 요청이 집중될 때 Redis와 Partition 병목 확인 |
| Distributed Products | 여러 상품 요청에서 Worker 확장 효과 확인 |
| Idempotency Replay | 중복 요청 폭증 상황의 정확성과 부하 확인 |
| Mass Expiration | 같은 시각에 대량 예약이 만료될 때 복구 정확성 확인 |
| Scale-out | Pod 자동 확장 시간과 Latency·Lag 변화 확인 |
| Failure Recovery | Pod와 Consumer 종료 후 재처리·복구 검증 |

모든 결과는 RPS만이 아니라 p50, p95, p99, 오류율, Redis CPU, Consumer Lag와 함께 기록합니다.

## Development Roadmap

- [ ] Phase 0 — 사용자·가게·상품과 로컬 인프라 구성
- [ ] Phase 1 — Redis Lua Script 기반 동기 예약
- [ ] Phase 1.1 — TTL, Sorted Set, Expiry Worker 기반 만료 처리
- [ ] Phase 2 — k6 기준 부하 테스트와 병목 분석
- [ ] Phase 3 — Kafka 예약 요청 대기열과 Consumer 멱등 처리
- [ ] Phase 4 — Kubernetes 다중 인스턴스와 자동 확장
- [ ] Phase 5 — V1/V2/V3 성능 비교 및 장애 실험 문서화

## Planned Project Structure

~~~text
ieum/
├── applications/
│   ├── ieum-api/
│   └── ieum-reservation-worker/
├── domains/
│   ├── member-domain/
│   ├── store-domain/
│   ├── product-domain/
│   └── reservation-domain/
├── infrastructures/
│   ├── mysql-adapter/
│   ├── redis-adapter/
│   └── kafka-adapter/
├── scripts/
│   └── redis/
├── load-tests/
│   └── k6/
├── deploy/
│   ├── docker-compose/
│   ├── kubernetes/
│   ├── prometheus/
│   └── grafana/
└── docs/
    ├── adr/
    └── performance/
~~~

## Out of Scope

초기 프로젝트에서는 예약의 정합성과 확장 과정에 집중하기 위해 다음 기능을 제외합니다.

- 실제 PG 결제와 환불
- 사업자등록번호 진위 확인
- 지도와 길찾기
- 개인화 추천과 AI
- 채팅, 리뷰, 쿠폰, 포인트
- 배달, 정산, 세금계산서

---

**IEUM connects local merchants and customers before good food goes to waste.**
