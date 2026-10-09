# 관측 가이드 — Prometheus + Grafana

todo 4 관측의 첫 단계입니다. 두 서버(`ieum-api`, `ieum-auth`)의 `/actuator/prometheus` 를 Prometheus 가 5초마다 긁고,
Grafana 에 **예약 정합성** 대시보드를 미리 올려 둡니다. 부하 테스트(k6) 를 돌리는 동안 "숫자가 맞는가" 와
"어디서 느려지는가" 를 한 화면에서 보는 것이 목적입니다.

| 구성 요소 | 위치 |
|---|---|
| Compose 서비스 `prometheus`, `grafana` (profile `monitoring`) | `IEUM_BE/docker-compose.yml` |
| 스크레이프 설정 | `IEUM_BE/monitoring/prometheus/prometheus.yml` |
| Grafana 데이터소스 (uid `ieum-prometheus`) | `IEUM_BE/monitoring/grafana/provisioning/datasources/prometheus.yml` |
| 대시보드 프로비저닝 | `IEUM_BE/monitoring/grafana/provisioning/dashboards/ieum.yml` |
| 대시보드 (uid `ieum-reservation`) | `IEUM_BE/monitoring/grafana/dashboards/reservation-correctness.json` |

---

## 1. 띄우기

```bash
cd IEUM_BE
# 평소처럼 DB·Redis 만 (monitoring 은 profile 이라 뜨지 않는다)
docker compose up -d
# 관측까지 함께
docker compose --profile monitoring up -d
# 관측만 내리기 (DB·Redis 는 유지)
docker compose --profile monitoring stop prometheus grafana
```

- 다른 프로젝트의 Redis 가 6379 를 쓰고 있으면 `REDIS_PORT=6380` 으로 띄운다 (관측과는 무관).
- 두 서버는 **호스트**에서 `bootRun` 으로 돈다. 컨테이너 안의 Prometheus 는 `host.docker.internal:8080`, `:8081` 로 긁는다.
  Linux 에서도 같은 이름이 풀리도록 두 서비스에 `extra_hosts: host.docker.internal:host-gateway` 를 넣어 두었다.

| 주소 | 용도 |
|---|---|
| http://localhost:9090 | Prometheus. `Status → Targets` 에서 `ieum-api`, `ieum-auth` 가 UP 인지 확인 |
| http://localhost:3001 | Grafana. 로그인 없이 Viewer 로 열리고 홈이 대시보드다. 편집은 `admin` / `GRAFANA_ADMIN_PASSWORD` (기본 `admin`) |

포트와 계정은 `.env` 의 `PROMETHEUS_PORT`, `GRAFANA_PORT`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` 로 바꾼다
(`.env.example` 참고). 3000 은 FE 개발 서버가 쓰므로 Grafana 는 3001 이다.

서버가 꺼져 있으면 타깃이 DOWN 으로 보이는 것이 정상이다. 대시보드 맨 아래 **스크레이프 상태** 패널(`up`) 이 0 이면
서버가 안 떠 있거나 `/actuator/prometheus` 가 막힌 것이다. 두 서버 모두 이 경로를 인증 없이 GET 으로 열어 두었다
(`ieum-auth` 는 SecurityConfig 에서 `GET /actuator/prometheus` 만 permitAll, 나머지는 여전히 401).

대시보드 JSON 은 파일 프로비저닝이라 UI 에서 저장할 수 없다(`allowUiUpdates: false`). 고치려면 JSON 을 고치고
30초 기다리거나 Grafana 를 재시작한다. UI 에서 만든 패널은 `Export → JSON` 으로 뽑아 파일에 반영한다.

### 부하 테스트와 함께 쓰기

1. `docker compose --profile monitoring up -d`
2. `ieum-api` 를 측정 전략으로 기동 (`STOCK_STRATEGY=redis` 등, [예약 경로 가이드](reservation-path-guide.md) 의 절차 그대로)
3. Grafana 시간 범위를 "Last 15 minutes", 새로 고침 5s 로 둔 채 k6 실행
4. 끝나면 시간 범위를 테스트 구간으로 좁혀 정합성 줄의 stat 값을 기록한다 (stat 은 **선택한 기간 전체의 합계**다)

Prometheus 의 스크레이프 간격이 5초라 1~2초짜리 버스트는 한두 점으로만 잡힌다. 정확한 총량은 stat 패널(`increase`)
과 k6 결과로 보고, 시계열은 모양(언제 몰렸는지) 을 보는 용도로 쓴다.

---

## 2. 지표 이름

Micrometer 이름의 `.` 은 `_` 가 되고, Counter 는 `_total`, Timer 는 `_seconds_{count,sum,max}` 가 붙는다.
아래 커스텀 지표는 모두 `ieum-api` 에만 있다.

| Micrometer | Prometheus | 태그 | 등록 위치 |
|---|---|---|---|
| `order.create.attempts` | `order_create_attempts_total` | `outcome=success\|conflict\|exhausted\|deadlock` | `OrderCreateRetrier` |
| `order.create.attempts.used` | `order_create_attempts_used_{count,sum,max,bucket}` | — | `OrderCreateRetrier` |
| `stock.redis.reserve` | `stock_redis_reserve_total` | `outcome=reserved\|replay\|in_flight\|duplicate\|sold_out` | `RedisStockDeduction` |
| `stock.redis.event` | `stock_redis_event_total` | `event=warmup\|compensated\|released\|release_skipped\|release_failed` | `RedisStockDeduction` |
| `stock.redis.script` | `stock_redis_script_seconds_{count,sum,max}` | — | `RedisStockDeduction` |
| `order.expiry` | `order_expiry_total` | `outcome=expired\|skipped\|failed` | `ExpiryWorker` |
| `order.pending.timeout` | `order_pending_timeout_total` | `outcome=canceled\|skipped\|failed` | `PendingTimeoutJob` |
| `stock.reconciliation` | `stock_reconciliation_total` | `outcome=checked\|mismatch\|missing\|corrected\|requeued` | `StockReconciliationJob` |

내장 지표: `http_server_requests_seconds_{count,sum,max}` (`uri`, `method`, `status`, `outcome`),
`hikaricp_connections_{active,pending,max}`, `hikaricp_connections_acquire_seconds_{count,sum,max}`, `jvm_memory_*`.
Prometheus 가 붙이는 `job` 라벨(`ieum-api` / `ieum-auth`) 로 서버를 나눈다.

`RedisStockDeduction` 지표는 `STOCK_STRATEGY=redis` 일 때만, `order.create.attempts` 는 그 외 전략일 때만 값이 생긴다.
전략에 없는 지표는 시계열 패널에서 "No data" 로 보이는 것이 정상이다.

---

## 3. 대시보드 패널

### 3.1 정합성 줄 — 불변식

맨 위 여섯 칸은 **선택한 기간 동안의 합계**(`increase(...[$__range])`) 다. 0 이면 초록, 0 이 아니면 빨강(또는 주황) 이 된다.
지표가 아직 없을 때도 0 이 보이도록 `or vector(0)` 을 붙였다. 서버가 꺼져 있어도 0 이므로 `up` 패널과 같이 본다.

| 패널 | PromQL | 기대값 | 의미 |
|---|---|---|---|
| 재고 불일치 (mismatch) | `sum(increase(stock_reconciliation_total{job="ieum-api", outcome="mismatch"}[$__range]))` | 0 (주황) | Reconciliation 이 Redis 재고 `stock:{itemId}`·활성 예약 집합 `stock:{itemId}:active` 가 DB 기대값과 다르다고 본 횟수 |
| 재고 키 유실 (missing) | 같은 식, `outcome="missing"` | 0 (주황) | Redis 에 재고 키가 없었다. warmup 전 상품이거나 Redis 데이터 유실 |
| 자동 보정 (corrected) | 같은 식, `outcome="corrected"` | **0** | 같은 차이가 두 번 연속 관측돼 Redis 를 고쳤다. 진짜 어긋남 |
| 재고 반환 실패 (release_failed) | `sum(increase(stock_redis_event_total{job="ieum-api", event="release_failed"}[$__range]))` | **0** | 취소·만료 후 Redis INCRBY 실패. 보정 전까지 재고가 덜 팔린다 |
| 만료 처리 실패 | `sum(increase(order_expiry_total{job="ieum-api", outcome="failed"}[$__range]))` | **0** | Expiry Worker 실패 |
| 승인 타임아웃 실패 | `sum(increase(order_pending_timeout_total{job="ieum-api", outcome="failed"}[$__range]))` | **0** | PENDING 자동 취소 실패 |

`mismatch` 와 `missing` 을 빨강이 아닌 주황으로 둔 이유: Reconciliation 은 "DB 에서 기대값을 읽고 → Redis 를 읽는" 사이에
커밋되는 주문과 엇갈릴 수 있어서, 부하 중에는 일시적인 `mismatch` 가 한 번 나올 수 있다. 그래서 코드도 같은 차이가
**두 번 연속**일 때만 보정한다. 엄격한 불변식은 `corrected = 0` 이고, `mismatch` 는 0 이 목표이되 1~2 회는 로그
(`재고 불일치: ... persisted=false`) 로 원인을 확인한다. 부하가 끝난 뒤 한 주기(`RECONCILIATION_INTERVAL`, 기본 60초)
지나서도 `mismatch` 가 오르면 진짜 어긋남이다.

아래 시계열 셋은 같은 지표를 초당 비율로 본다.

| 패널 | PromQL | 읽는 법 |
|---|---|---|
| Reconciliation 결과 | `sum by (outcome) (rate(stock_reconciliation_total{job="ieum-api"}[$__rate_interval]))` | `checked` 만 주기적으로 오르고 나머지는 0 에 붙어 있어야 한다 |
| Redis 재고 이벤트 | `sum by (event) (rate(stock_redis_event_total{job="ieum-api"}[$__rate_interval]))` | `compensated` = Redis 차감 후 DB 커밋이 실패해 되돌린 횟수 (0 이 아니어도 정합성은 지켜진 것. 창이 몇 번 열렸는지를 잰다). `released` / `release_skipped` = 취소·만료 반환 / 이미 반환돼 건너뜀 |
| 만료 · 승인 타임아웃 결과 | `sum by (outcome) (rate(order_expiry_total{job="ieum-api"}[$__rate_interval]))`, `order_pending_timeout_total` 도 같은 식 | `failed` 는 0 |

**Redis 예약 판정** (누적 영역) 은 `sum by (outcome) (rate(stock_redis_reserve_total{job="ieum-api"}[$__rate_interval]))` 다.
오른쪽 **기간 내 reserved 합계** 는 `sum(increase(stock_redis_reserve_total{job="ieum-api", outcome="reserved"}[$__range]))` 로,
부하 테스트 구간으로 좁혔을 때 **상품 재고 수 이하**여야 한다 (넘으면 초과 예약). DB 쪽 확인 SQL 과 함께 본다.

- `sold_out` / `duplicate` — 정상 거절 (409). 대부분의 요청이 여기로 간다
- `replay` — 같은 멱등키로 다시 온 요청. 앞 주문 id 를 돌려준다
- `in_flight` — 같은 멱등키의 앞 요청이 아직 커밋 전

### 3.2 처리 줄 — `POST /api/orders`

| 패널 | PromQL |
|---|---|
| 요청률 by status | `sum by (status) (rate(http_server_requests_seconds_count{job="ieum-api", uri="/api/orders", method="POST"}[$__rate_interval]))` |
| 응답 시간 평균 | `sum by (status) (rate(http_server_requests_seconds_sum{…}[$__rate_interval])) / sum by (status) (rate(http_server_requests_seconds_count{…}[$__rate_interval]))` |
| 응답 시간 최대 | `max by (status) (http_server_requests_seconds_max{…})` |
| 주문 생성 시도 결과 | `sum by (outcome) (rate(order_create_attempts_total{job="ieum-api"}[$__rate_interval]))` |
| 성공까지 평균 시도 수 (오른쪽 축) | `sum(rate(order_create_attempts_used_sum[$__rate_interval])) / sum(rate(order_create_attempts_used_count[$__rate_interval]))` |

- 5xx 는 0 이어야 한다. 2xx + 409 의 합이 k6 가 보낸 요청 수와 맞는지 본다.
- `_max` 는 Micrometer 가 최근 짧은 창(기본 2분, 3구간 회전) 의 최댓값을 내보내는 게이지라, 한 번 튄 값이 잠시 남는다.
- `order.create.attempts` 의 단위가 섞여 있다. `success`·`exhausted` 는 **요청** 단위, `conflict`·`deadlock` 은
  **실패한 시도** 단위다(재시도마다 1). optimistic 전략에서 `conflict` 가 `success` 보다 큰 것은 이상이 아니다.
  `exhausted` 는 재시도 상한을 넘겨 실패로 끝난 요청이므로 0 이 목표다.

### 3.3 자원 줄

| 패널 | PromQL | 읽는 법 |
|---|---|---|
| HikariCP 커넥션 | `sum by (job) (hikaricp_connections_active)`, `…_pending`, `…_max` | active 가 max 에 붙고 pending 이 쌓이면 DB 커넥션이 병목. redis 전략은 거절이 커넥션 없이 끝나 pending 이 낮아야 한다 |
| 커넥션 획득 대기 | `sum by (job) (rate(hikaricp_connections_acquire_seconds_sum[…])) / sum by (job) (rate(hikaricp_connections_acquire_seconds_count[…]))`, `max by (job) (hikaricp_connections_acquire_seconds_max)` | 비관적 락·조건부 UPDATE 에서 오르는 값 |
| Redis Lua 스크립트 | `sum(rate(stock_redis_script_seconds_sum[…])) / sum(rate(stock_redis_script_seconds_count[…]))`, `max(stock_redis_script_seconds_max)` | 예약 판정 한 번의 왕복 시간 |
| JVM 힙 | `sum by (job) (jvm_memory_used_bytes{area="heap"})`, `…committed…` | 두 서버 힙 |
| 스크레이프 상태 | `up{job=~"ieum-api\|ieum-auth"}` | 1 = 긁힘, 0 = 서버 꺼짐·경로 막힘 |

`$__rate_interval` 은 Grafana 가 스크레이프 간격(데이터소스 `timeInterval: 5s`) 과 화면 해상도로 정하는 창이다.

---

## 4. 후속 과제 — p99 를 보려면

지금 `http_server_requests_seconds` 에는 히스토그램 버킷(`_bucket`) 이 없어서 평균과 최대만 볼 수 있다.
k6 결과의 p99 와 같은 값을 Grafana 에서 보려면 `ieum-api` 의 `application.yaml` 에 다음을 추가한다.

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      # 선택: 버킷 범위를 좁혀 시계열 수를 줄인다
      minimum-expected-value:
        http.server.requests: 1ms
      maximum-expected-value:
        http.server.requests: 5s
```

그다음 대시보드에 다음 패널을 추가한다.

```promql
histogram_quantile(0.99,
  sum by (le) (rate(http_server_requests_seconds_bucket{job="ieum-api", uri="/api/orders", method="POST"}[$__rate_interval])))
```

`stock.redis.script` 와 HikariCP 획득 시간도 같은 방식(`percentiles-histogram.stock.redis.script: true` 등) 으로 켤 수 있다.
버킷은 uri·status 조합마다 수십 개의 시계열을 만들므로 필요한 지표에만 켠다.

그 밖에 남은 것

- Prometheus 경보 규칙: `increase(stock_reconciliation_total{outcome="corrected"}[5m]) > 0`,
  `increase(stock_redis_event_total{event="release_failed"}[5m]) > 0` 를 alert 로 걸기
- 서버를 컨테이너·Kubernetes 로 옮기면 `prometheus.yml` 의 `host.docker.internal` 타깃을 서비스 디스커버리로 바꾼다
- `/actuator/prometheus` 는 지금 인증 없이 열려 있다. 운영에서는 관리 포트(`management.server.port`) 를 분리해
  외부에 노출하지 않는다
