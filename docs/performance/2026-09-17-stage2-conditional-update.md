# 부하 테스트 기록 — (선택) 조건부 UPDATE 한 문장 (conditional)

todo 2.2 의 2단계 (선택) 7 번. `@Version` 도 재시도도 없이 `UPDATE ... SET remaining = remaining - ? WHERE id = ? AND remaining >= ?`
한 문장으로 차감하는 중간 데이터 포인트다. 비교 기준은
[2단계 라운드 2](./2026-09-14-stage2-optimistic.md#라운드-2--flush-순서-변경--데드락-재시도-2026-09-17) (09-17, 같은 날 앞서 측정).
구현 배경과 절차는 [조건부 UPDATE 가이드](../guides/stage2-conditional-update-guide.md).

## 실행 조건

| 항목 | 값 |
|---|---|
| `STOCK_STRATEGY` | `conditional` — `ConditionalUpdateStockDeduction`. 영속성 컨텍스트의 스냅샷으로 재고 부족을 먼저 거르고, 통과하면 조건부 UPDATE 한 문장. UPDATE 가 0건이면 409 |
| `STOCK_RETRY_MAX_ATTEMPTS` / `STOCK_RETRY_BACKOFF` | 3 / `PT0.01S` (설정은 그대로지만 이 전략에서는 충돌 예외가 나지 않아 재시도 계층은 항상 1회에 통과) |
| VU / 총 요청 | 100 / 10,000 (소비자 1인당 1건, `quantity` 1) |
| `SQL_LOG_LEVEL` / `SQL_BIND_LOG_LEVEL` | `warn` / `off` |
| 기동 방식 | `bootJar` → `IEUM_BE` 에서 `java -jar` (라운드 1·2 와 동일). 인증 서버는 IntelliJ 실행 그대로 (로그인 구간만 관여) |
| API 서버 HikariCP `maximum-pool-size` | 20 (기본값) |
| 코드 | `dev_be` `71e7503` — `StoresItemsRepository.deductIfAvailable` / `restoreIfWithinInitial`, `ConditionalUpdateStockDeduction`, `InsufficientStock()` 생성자 |
| 라운드 전 | MySQL 이 이날 19:02 재기동되어 시드가 비어 있었음 → `seed-loadtest.sql` 재실행. SQL 순서 확인용 예약 1건 후 `reset-loadtest.sql`, API 서버 재기동으로 카운터 0. 재고 100 · 주문 0 · `version` 0 · AUTO_INCREMENT 1 |
| SQL 순서 확인 | MySQL general log (TABLE) 로 예약 1건: `select stores_items` → `select count(...) users_orders` → **`update stores_items set remaining_quantity=(remaining_quantity-1) where id=1 and remaining_quantity>=1`** → `insert into users_orders`. SET 절에 `version` 없음 |
| 스크랩 | 0.5초 간격 → `scripts/k6/out/stage2c-20260917-205700.prom` (저장소 제외) |
| 행 락 통계 (신규) | k6 전후 `SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'`. MySQL 재기동 이후 측정 전까지 waits 0 / time 0 이라 사후 값이 곧 이 라운드의 증가분 |
| 실행 환경 | 이전 라운드와 동일 (Ryzen 5 5600, 16GB, Windows 11, Docker Desktop, k6 v2.2.0 호스트 실행) |

## 결과 요약

| 항목 | 값 (conditional) | 값 (optimistic 라운드 2) | 출처 |
|---|---|---|---|
| 201 (예약 성공) | **100** | 100 | k6 `created 201` |
| 409 (재고 부족·중복) | 9,900 | 9,326 | k6 `sold out 409` |
| 503 (재시도 소진) | **0** | 574 | k6 `contention 503` |
| 500 | 0 | 0 | 세 체크 합 10,000 |
| 초과 예약 | **0** | 0 | |
| DB `PENDING` 주문 수 / 수량 합 | 100 / 100 | 100 / 100 | 아래 SQL |
| DB `remaining_quantity` / `version` | 0 / **0** | 0 / 100 | 아래 SQL |
| 불변식 `initial = remaining + active` | **성립** (100 = 0 + 100) | 성립 | |
| `order.create.attempts{success / conflict / exhausted / deadlock}` | 100 / **0** / **0** / 0 | 100 / 1,894 / 574 / 0 | Prometheus |
| 성공까지 시도 횟수 (1회 / 2회 / 3회) | **100 / 0 / 0** | 59 / 18 / 23 | `order_create_attempts_used_bucket` |
| 커넥션 획득 횟수 | **10,000** (요청당 정확히 1회) | 11,322 | `acquire_seconds_count` |
| 롤백된 주문 INSERT (`MAX(id) − COUNT(*)`) | 0 | 0 | 아래 SQL |
| `hikaricp.connections.pending` 최대 / `active` 최대 | 80 / 20 | 79 / 20 | 스크랩 |
| `hikaricp.connections.acquire` 평균 / 최대 | 95ms (950.6s ÷ 10,000) / 1.00s | 88ms / 1.09s | Prometheus |
| 예약 요청 지연 med / p95 / p99 / max | 105ms / **236ms** / **669ms** / 1.67s | 93ms / 491ms / 790ms / 1.82s | k6 `{ name:create-order }` |
| 로그인 지연 med / p95 / p99 / max | 93ms / 177ms / 185ms / 282ms | 93ms / 180ms / 195ms / 510ms | k6 `{ name:login }` |
| 예약 구간 시간 | 13.2s | 13.9s | k6 진행 줄 `00m13.2s` |
| 예약 처리량 | ≈ 758 req/s (10,000 ÷ 13.2s) | ≈ 719 req/s | 계산 |
| 재고 소진까지 | **0.76초** (21:00:13.73 → 21:00:14.49) | 약 5초 | DB `created_at` |
| `Innodb_row_lock_waits` / `Innodb_row_lock_time` (신규) | **118 회 / 13,903ms** (평균 117ms, 최대 227ms) | (미측정) | `SHOW GLOBAL STATUS` |
| InnoDB `LATEST DETECTED DEADLOCK` | 절 없음 (19:02 재기동 이후 데드락 0) | 절 없음 | `SHOW ENGINE INNODB STATUS` |

네 측정을 나란히 놓으면:

| | 1. naive (09-16, 계측) | 2. optimistic 라운드 1 | 2. optimistic 라운드 2 | (선택) conditional |
|---|---|---|---|---|
| 201 | 1,996 | **100** | **100** | **100** |
| 초과 예약 | 1,896 | 0 | 0 | 0 |
| 500 | 0 | 1,926 | 0 | 0 |
| 503 (재고가 있는데 답을 못 줌) | 0 | 4 | 574 (5.7%) | **0** |
| 충돌 시도 / 재시도 | 0 | 137 | 1,894 / 1,320 | **0 / 0** |
| 롤백된 주문 INSERT | 0 | ≈ 2,048 | 0 | 0 |
| DB `version` | 0 | 100 | 100 | **0** |
| p99 / p95 / 중앙값 | 633 / — / 108ms | 716 / 381 / 114ms | 790 / 491 / 93ms | **669 / 236** / 105ms |
| 처리량 | ≈ 529 req/s | ≈ 588 req/s | ≈ 719 req/s | ≈ 758 req/s |
| `pending` 최대 / `acquire` 평균 | 80 / 141ms | 80 / 119ms | 79 / 88ms | 80 / 95ms |
| 재고 소진까지 | 약 10초 | 약 7초 | 약 5초 | **0.76초** |

## 해석

**503 은 0 이다.** 재고가 남아 있는 동안 UPDATE 에 도달한 트랜잭션은 X 락에서 기다렸다가 **현재 값** 으로 `remaining >= 1`
을 평가하므로, 앞 트랜잭션이 커밋했다는 이유로 실패하지 않는다. 라운드 2 의 574 는 재고 경합 자체의 값이 아니라
"version 불일치를 실패로 보고 상한 3 에서 포기하는" 낙관적 재시도의 산물이었다. 같은 행, 같은 X 락 대기인데
락을 받은 뒤 무엇을 비교하느냐(version 인지 재고인지) 만 다르고, 그 차이가 574 와 0 이다.

**재시도 계층은 한 번도 돌지 않았다.** `conflict` 0, `exhausted` 0, 시도 횟수 100 건 전부 1회, 커넥션 획득이 요청 수와 정확히 같은 10,000.
라운드 2 는 재시도 1,320 회만큼 커넥션 획득이 많았다 (11,322). 재시도 빈은 경로에 그대로 있고 이 전략에서는 통과만 한다.

**재고 100 이 0.76초에 소진됐다.** 라운드 2 의 5초, 라운드 1 의 7초, naive 의 10초와 비교하면 가장 빠르다. 성공 트랜잭션 100 개가
한 행의 X 락에 직렬화되어 평균 **7.6ms** 간격으로 커밋한 셈이다 (UPDATE → INSERT → commit 왕복 세 번). 라운드 2 에서는
같은 줄에 선 트랜잭션 대부분이 version 검사에서 죽고 다시 줄을 섰기 때문에 성공 100 개를 채우는 데 5초가 걸렸다.

**행 락 대기 실측 — 평균 117ms, 최대 227ms, 118 회.** 이번에 처음 잰 값이다. 대기 118 회는 UPDATE 까지 간 트랜잭션 중 락을 즉시 못 받은 수이고,
소진 전 0.76초 동안의 일이다. 평균 117ms 는 락 보유 시간(≈ 7.6ms) 이 아니라 **줄 길이 × 보유 시간** 이다. 풀 크기 20 이라 최대 19 개가
같은 행에 줄을 서고, 19 × 7.6ms ≈ 144ms 가 최대 대기의 자연스러운 상한이며 실측 최대 227ms 와 같은 자릿수다.
라운드 2 해석에서 "백오프 `[0, 10ms × 시도]` 가 X 락 보유 시간보다 짧았을 것" 이라고 추정했는데, 실제로 비교해야 할 값은 보유 시간이 아니라
줄 대기 시간 117ms 였고, 10~30ms 백오프는 그보다 한 자릿수 짧았다. 재시도가 같은 줄 끝에 다시 서는 것이 그래서 당연했다.

**p99 는 790 → 669ms, p95 는 491 → 236ms.** 꼬리가 내려간 이유는 두 가지다. 경합 구간이 5초에서 0.76초로 줄어 락 대기·백오프를 겪는
요청의 절대 수가 줄었고, 재시도 1,320 회가 풀 앞의 줄에서 사라졌다. 중앙값은 93 → 105ms 로 오히려 조금 올랐는데, 라운드 2 는 소진 후
409 로 끝나는 짧은 요청이 93% 였고 이번은 99% 라 분모 구성이 다르며, `acquire` 평균 88 → 95ms 차이(편차 안)와 같은 방향이다.
**p99 669ms 는 재시도가 전혀 없는데도 naive 의 633ms 와 같은 수준** 이다. 즉 이 부하에서 p99 의 정체는 전략이 아니라 VU 100 / 풀 20 의
커넥션 대기(`pending` 80, `acquire` 최대 1.0s) 이고, 전략은 그 위에서 p95 와 경합 구간 길이를 바꾼다.

**커넥션 대기는 네 측정 모두 같다.** `pending` 최대 80, `active` 20. 예약 구간 13.2초 내내 76~80 이 유지되다가 끝나면서 0. 실험 조건의 성질이다.

### 낙관적 락 비용의 분해 (라운드 2 와 이번의 차이가 곧 "재시도" 의 비용)

| | optimistic 라운드 2 | conditional | 차이의 원인 |
|---|---|---|---|
| 503 | 574 | 0 | version 비교 → 재고 비교 |
| 커넥션 획득 | 11,322 | 10,000 | 재시도 1,320 회 |
| 재고 소진 시간 | 5초 | 0.76초 | 실패 후 재줄서기 |
| p95 / p99 | 491 / 790ms | 236 / 669ms | 경합 구간 단축 + 재시도 제거 |
| 처리량 | 719 req/s | 758 req/s | |
| DB `version` | 100 | 0 | `@Version` 우회 |

정합성(201 정확히 100, 불변식) 은 둘 다 같다. `@Version` 이 추가로 준 것은 없고, 비용만 "충돌 검출 + 재시도" 로 들어갔다.
단, 이것은 **재고 한 행만 지키면 되는 경로** 에서의 결론이다. 여러 행·여러 불변식을 한 트랜잭션에서 지켜야 하면 조건부 UPDATE 한 문장으로는
표현이 안 되고 `@Version` 또는 비관적 락이 필요하다.

### 조건부 UPDATE 로 해결되는 것과 남는 것

- 해결: 초과 예약 0, 503 0, 재시도 0, 롤백된 INSERT 0, 데드락 0. `@Version` 없이 (version 0) 정합성 성립
- 남는 것
  - **재고 한 행의 X 락 직렬화.** 성공 100 개가 0.76초에 몰린 것은 이 부하에서 한 상품의 성공 처리 상한이 ≈ 130 건/s 라는 뜻이다.
    상품이 하나뿐인 시나리오라 전체 처리량은 커넥션 대기가 결정하지만, 인기 상품 하나에 트래픽이 몰리면 이 직렬화가 상한이 된다
  - **규칙이 SQL 로 내려갔다.** `remaining >= qty` 는 `decreaseQuantity` 가 아니라 `WHERE` 절에 있다. 영속성 컨텍스트의 엔티티는 UPDATE 후 stale 이며,
    `stores_items.remaining_quantity` 를 엔티티 더티 체킹으로 고치는 다른 경로가 생기면 그 경로의 version 검사는 이 UPDATE 를 못 본다.
    한 컬럼을 두 방식으로 쓰지 않는다는 규칙이 필요하다
  - 중복 활성 예약 검사의 틈 (변함없음, 3단계에서 Lua 안으로)
  - 커넥션 대기 (실험 조건)
- 이 셋(직렬화·규칙의 위치·중복 검사) 이 3단계에서 Redis 가 가져가야 할 것의 목록이다

## DB 확인 쿼리와 결과

```text
order_state	cnt	qty
PENDING	100	100

initial_quantity	remaining_quantity	version
100	0	0

max_id	cnt	rolled_back_inserts
100	100	0

MIN(created_at)	MAX(created_at)
2026-09-17 21:00:13.732002	2026-09-17 21:00:14.487476
```

`SHOW ENGINE INNODB STATUS\G` 에 `LATEST DETECTED DEADLOCK` 절이 없다 (MySQL 19:02 재기동 이후 데드락 0).

## 행 락 통계 (k6 전 → 후)

```text
                          전     후
Innodb_row_lock_waits     0  →  118
Innodb_row_lock_time      0  →  13903   (ms)
Innodb_row_lock_time_avg  0  →  117
Innodb_row_lock_time_max  0  →  227
```

## 스크랩 타임라인 (초당 `pending` 최대 | success / conflict / exhausted / deadlock 누적)

```text
21:00:13  76 |   18 /    0 /    0 / 0   ← 예약 시작 (첫 주문 created_at 21:00:13.73)
21:00:14  78 |  100 /    0 /    0 / 0   ← 재고 0 (마지막 주문 21:00:14.49). 이후 카운터 변화 없음
21:00:16  78 |  100 /    0 /    0 / 0
21:00:18  79 |  100 /    0 /    0 / 0
21:00:20  77 |  100 /    0 /    0 / 0
21:00:22  80 |  100 /    0 /    0 / 0
21:00:24  79 |  100 /    0 /    0 / 0
21:00:25   0 |  100 /    0 /    0 / 0   ← 예약 구간 끝 (13.2s)
```

## k6 출력 (진행 줄 생략)

```text
PS D:\dev\IEUM\IEUM_BE> k6 run scripts/k6/create-order.js

     execution: local
        script: scripts/k6/create-order.js
        output: -

     scenarios: (100.00%) 1 scenario, 100 max VUs, 10m30s max duration (incl. graceful stop):
              * order: 10000 iterations shared among 100 VUs (maxDuration: 10m0s, gracefulStop: 30s)


  █ THRESHOLDS

    http_req_duration{name:create-order}
    ✓ 'p(99)<60000' p(99)=669.27ms

    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=185.05ms


  █ TOTAL RESULTS

    checks_total.......: 30000  152.182882/s
    checks_succeeded...: 33.33% 10000 out of 30000
    checks_failed......: 66.66% 20000 out of 30000

    ✗ created 201
      ↳  1% — ✓ 100 / ✗ 9900
    ✗ sold out 409
      ↳  99% — ✓ 9900 / ✗ 100
    ✗ contention 503
      ↳  0% — ✓ 0 / ✗ 10000

    HTTP
    http_req_duration..............: avg=115.09ms med=94.62ms  p(95)=186.83ms p(99)=340.15ms max=1.67s
      { expected_response:true }...: avg=115.94ms med=93.16ms  p(95)=178.28ms p(99)=242.03ms max=1.67s
      { name:create-order }........: avg=124.85ms med=104.59ms p(95)=236.1ms  p(99)=669.27ms max=1.67s
      { name:login }...............: avg=105.33ms med=93.09ms  p(95)=176.85ms p(99)=185.05ms max=281.85ms
    http_req_failed................: 49.50% 9900 out of 20000
    http_reqs......................: 20000  101.455255/s

    EXECUTION
    iteration_duration.............: avg=125.93ms med=105.32ms p(95)=236.57ms p(99)=669.58ms max=1.77s
    iterations.....................: 10000  50.727627/s
    vus............................: 100    min=0             max=100
    vus_max........................: 100    min=100           max=100

    NETWORK
    data_received..................: 17 MB  86 kB/s
    data_sent......................: 12 MB  60 kB/s


order ✓ [======================================] 100 VUs  00m13.2s/10m0s  10000/10000 shared iters
```

## Prometheus 최종 스냅샷

```text
order_create_attempts_total{outcome="conflict"} 0.0
order_create_attempts_total{outcome="deadlock"} 0.0
order_create_attempts_total{outcome="exhausted"} 0.0
order_create_attempts_total{outcome="success"} 100.0
order_create_attempts_used_bucket{le="1.0"} 100
order_create_attempts_used_bucket{le="2.0"} 100
order_create_attempts_used_bucket{le="3.0"} 100
order_create_attempts_used_count 100
order_create_attempts_used_sum 100.0
order_create_attempts_used_max 1.0
hikaricp_connections_acquire_seconds_count{pool="IeumHikariPool"} 10000
hikaricp_connections_acquire_seconds_sum{pool="IeumHikariPool"} 950.6017629
hikaricp_connections_acquire_seconds_max{pool="IeumHikariPool"} 1.0020818
hikaricp_connections_max{pool="IeumHikariPool"} 20.0
```

## 다음 라운드 전

`reset-loadtest.sql` 로 초기화해 둔 상태다 (재고 100 · 주문 0 · `version` 0 · AUTO_INCREMENT 1).
2단계의 선택 데이터 포인트까지 끝났다. 다음은 3단계 Redis Lua 다. 이 라운드가 3단계에 넘기는 비교 포인트:

- 재고 소진 0.76초 (한 행 직렬화의 성공 처리 상한 ≈ 130 건/s) 가 Redis 원자 연산에서 얼마나 짧아지는가
- `Innodb_row_lock_waits` 118 → 0 이 되는가 (재고 행에 UPDATE 가 사라지므로)
- p99 669ms 가 커넥션 대기의 값이라면, DB 왕복이 줄어도 풀 20 / VU 100 인 한 크게 안 내려갈 것. 그것이 확인되면 병목의 위치가 확정된다
- 부수 관찰: SQL 순서 확인 시 INSERT 된 `order_price` 가 5,000 (`original_price`) 이었다. `sale_price` 3,000 이 맞는지 `UsersOrders` 생성자를 확인할 것 (측정과 무관)
