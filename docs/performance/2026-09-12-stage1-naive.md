# 부하 테스트 기록 — 1단계 잠금 없음 (naive)

- 날짜: 2026-09-12
- 목적: 잠금 없이 "조회 후 덮어쓰기" 로 재고를 차감하면 초과 예약이 실제로 발생하는지 재현 ([ADR-0003](../adr/0003-stock-deduction-concurrency.md) 1단계)
- 스크립트: `IEUM_BE/scripts/k6/create-order.js`
- 시드: `IEUM_BE/scripts/sql/seed-loadtest.sql` (소비자 10,000, 재고 100 상품 1개)

## 실행 조건

| 항목 | 값 |
|---|---|
| `STOCK_STRATEGY` | `naive` (기본값) |
| VU | 100 |
| 총 요청 | 10,000 (소비자 1인당 1건, `quantity` 1) |
| `NaiveStockDeduction` 의 `Thread.sleep` | 없음 |
| `SQL_LOG_LEVEL` / `SQL_BIND_LOG_LEVEL` | **기본값 `debug` / `trace` (로그 켜진 채 실행)** |
| `ACCESS_TOKEN_TTL` | 기본값 `PT15M` |
| API 서버 HikariCP `maximum-pool-size` | 20 (기본값) |
| 인증 서버 HikariCP `maximum-pool-size` | 5 (기본값) |
| MySQL | 8.4, Docker Compose (`--max-connections=500`) |
| Redis | 7.4 (이 단계에서는 재고에 미사용) |
| 실행 환경 | AMD Ryzen 5 5600 (6C/12T), RAM 16GB, Windows 11, Docker Desktop 29.6.1. k6 v2.2.0 을 호스트에서 실행. 두 서버·MySQL·k6 가 같은 PC |

SQL 로그가 켜진 채 실행되어 지연 수치(p99, 처리량)에 로그 출력 비용이 섞여 있을 수 있다. 2026-09-14 에 로그를 끄고 재측정한 결과는 아래 [재측정](#재측정--sql-로그-끔-2026-09-14) 절에 있으며, **2·3단계와 지연을 비교할 때는 그 절의 값을 쓴다** (결과적으로 차이는 편차 안이었다).

## 결과 요약

| 항목 | 값 | 출처 |
|---|---|---|
| 201 (예약 성공) | **1,996** | k6 `created 201` 체크 |
| 409 (재고 부족) | 8,004 | k6 `sold out 409` 체크 |
| 401 / 500 | 0 | 두 체크의 합 = 10,000 |
| 초과 예약 | **1,896** (성공 1,996 − 재고 100) | |
| DB `PENDING` 주문 수 / 수량 합 | 1,996 / 1,996 | 아래 SQL |
| DB `remaining_quantity` | 0 | 아래 SQL |
| 불변식 `initial = remaining + active` | **깨짐** (100 ≠ 0 + 1,996) | |
| 예약 요청 지연 med / p95 / p99 / max | 102ms / 531ms / 670ms / 1.35s | k6 `{ name:create-order }` |
| 로그인 지연 med / p95 / p99 / max | 84ms / 158ms / 170ms / 550ms | k6 `{ name:login }` |
| 예약 구간 시간 | 19.4s | k6 진행 줄 `00m19.4s` |
| 예약 처리량 | ≈ 515 req/s (10,000 ÷ 19.4s) | 계산 |
| 전체 실행 시간 | 3m05.4s (로그인 setup ≈ 2m46s 포함) | k6 `running (03m05.4s)` |

k6 요약의 `http_req_failed 40.02%` 는 409 응답 8,004건을 k6 가 실패로 센 것이며 오류가 아니다. `http_reqs 107.86/s`, `iterations 53.93/s` 는 분모에 로그인 setup 시간이 포함되어 있어 예약 처리량으로 쓰지 않는다.

## 해석

잠금 없는 차감은 `SELECT remaining` → `UPDATE remaining = (읽은 값 − 1)` 두 문장으로 이루어진다. 동시에 같은 값을 읽은 트랜잭션들이 각자 "읽은 값 − 1" 을 덮어쓰므로 앞선 차감이 사라진다(lost update). VU 100개가 계속 겹치는 동안 재고가 좀처럼 줄지 않아, 실제 재고의 약 20배가 통과한 뒤에야 0 에 닿았다.

MySQL 기본 격리 수준(REPEATABLE READ)에서 각 트랜잭션이 읽은 값은 모두 커밋된 값이다. 즉 dirty read 가 아니라 **오래된 커밋 값을 근거로 계산한 결과를 덮어쓰는 lost update** 이며, `UPDATE` 가 행 잠금을 잡더라도 잠금 이전에 계산된 값이 그대로 기록되므로 막지 못한다. 2단계(`@Version`)와 3단계(Redis Lua)는 각각 "읽은 값이 아직 유효한가" 를 쓰기 시점에 검사하거나, 읽기와 쓰기를 한 연산으로 합쳐 이 틈을 없앤다.

## DB 확인 쿼리와 결과

```sql
SELECT order_state, COUNT(*) AS cnt, SUM(quantity) AS qty
  FROM users_orders o
  JOIN stores_items i ON i.id = o.store_item_id
 WHERE i.uid = '33333333-3333-3333-3333-333333333333'
 GROUP BY order_state;

SELECT initial_quantity, remaining_quantity
  FROM stores_items
 WHERE uid = '33333333-3333-3333-3333-333333333333';
```

```text
order_state	cnt	qty
PENDING	1996	1996

initial_quantity	remaining_quantity
100	0
```

## k6 출력 전문

```text
PS D:\dev\IEUM\IEUM_BE> k6 run scripts/k6/create-order.js

         /\      Grafana   /‾‾/
    /\  /  \     |\  __   /  /
   /  \/    \    | |/ /  /   ‾‾\
  /          \   |   (  |  (‾)  |
 / __________ \  |_|\_\  \_____/


     execution: local
        script: scripts/k6/create-order.js
        output: -

     scenarios: (100.00%) 1 scenario, 100 max VUs, 10m30s max duration (incl. graceful stop):
              * order: 10000 iterations shared among 100 VUs (maxDuration: 10m0s, gracefulStop: 30s)



  █ THRESHOLDS

    http_req_duration{name:create-order}
    ✓ 'p(99)<60000' p(99)=670.05ms

    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=170.11ms


  █ TOTAL RESULTS

    checks_total.......: 20000  107.863395/s
    checks_succeeded...: 50.00% 10000 out of 20000
    checks_failed......: 50.00% 10000 out of 20000

    ✗ created 201
      ↳  19% — ✓ 1996 / ✗ 8004
    ✗ sold out 409
      ↳  80% — ✓ 8004 / ✗ 1996

    HTTP
    http_req_duration..............: avg=140.7ms  med=88.49ms  p(95)=483.38ms p(99)=590.5ms  max=1.35s
      { expected_response:true }...: avg=164.84ms med=85.66ms  p(95)=521.85ms p(99)=645.12ms max=1.35s
      { name:create-order }........: avg=186.52ms med=102.38ms p(95)=530.69ms p(99)=670.05ms max=1.35s
      { name:login }...............: avg=94.88ms  med=83.73ms  p(95)=158.13ms p(99)=170.11ms max=549.85ms
    http_req_failed................: 40.02% 8004 out of 20000
    http_reqs......................: 20000  107.863395/s

    EXECUTION
    iteration_duration.............: avg=187.22ms med=102.9ms  p(95)=531.06ms p(99)=677.65ms max=1.35s
    iterations.....................: 10000  53.931697/s
    vus............................: 100    min=0             max=100
    vus_max........................: 100    min=100           max=100

    NETWORK
    data_received..................: 17 MB  92 kB/s
    data_sent......................: 12 MB  63 kB/s




running (03m05.4s), 000/100 VUs, 10000 complete and 0 interrupted iterations
order ✓ [======================================] 100 VUs  00m19.4s/10m0s  10000/10000 shared iters
```

## 재측정 — SQL 로그 끔 (2026-09-14)

위 실행 조건에서 `SQL_LOG_LEVEL=warn`, `SQL_BIND_LOG_LEVEL=off` 만 바꾸어 같은 시나리오를 다시 돌렸다.
2·3단계와 지연을 비교할 때는 이 절의 값을 기준선으로 쓴다.

### 실행 조건 (달라진 것만)

| 항목 | 값 |
|---|---|
| `SQL_LOG_LEVEL` / `SQL_BIND_LOG_LEVEL` | **`warn` / `off`** (실행 중 두 서버 로그에 SQL 문장 0줄, WARN·ERROR 0건 확인) |
| 기동 방식 | `bootJar` 로 만든 jar 를 `IEUM_BE` 에서 `java -jar` 로 실행. 로그 레벨은 OS 환경변수로 넘김 (`.env` 의 `spring.config.import` 보다 OS 환경변수가 우선) |
| 라운드 전 | `reset-loadtest.sql` 로 재고 100 · 주문 0 · `version` 0 확인 |

### 결과 요약

| 항목 | 값 (로그 끔, 09-14) | 값 (로그 켬, 09-12) |
|---|---|---|
| 201 (예약 성공) | **2,000** | 1,996 |
| 409 (재고 부족) | 8,000 | 8,004 |
| 401 / 500 | 0 | 0 |
| 초과 예약 | **1,900** | 1,896 |
| DB `PENDING` 주문 수 / 수량 합 | 2,000 / 2,000 | 1,996 / 1,996 |
| DB `remaining_quantity` | 0 | 0 |
| 불변식 `initial = remaining + active` | 깨짐 (100 ≠ 0 + 2,000) | 깨짐 |
| 예약 요청 지연 med / p95 / p99 / max | 104ms / 577ms / 663ms / 1.88s | 102ms / 531ms / 670ms / 1.35s |
| 로그인 지연 med / p95 / p99 / max | 77ms / 150ms / 164ms / 864ms | 84ms / 158ms / 170ms / 550ms |
| 예약 구간 시간 | 20.3s | 19.4s |
| 예약 처리량 | ≈ 493 req/s (10,000 ÷ 20.3s) | ≈ 515 req/s |
| 전체 실행 시간 | 2m56.9s (로그인 setup ≈ 2m36s 포함) | 3m05.4s |

### 해석

SQL 로그를 꺼도 예약 요청 p99 는 670ms → 663ms, 중앙값은 102ms → 104ms 로 실행 간 편차 안에 있다.
이 부하(VU 100, 커넥션 풀 20)에서는 로그 출력이 병목이 아니었고, 09-12 기록에 달아 둔 "로그 비용이 섞여 있다" 는
단서는 실측으로 해소됐다. 초과 예약 건수(약 1,900)도 재현되어 lost update 해석은 그대로다.

지연의 대부분은 로그가 아니라 다른 곳에 있다. 후보는 VU 100 이 커넥션 20 개를 나눠 쓰는 HikariCP 대기와
트랜잭션당 두 왕복(`SELECT` → `UPDATE` + 주문 `INSERT`)이며, 2단계에서 `hikaricp.connections.pending` 을 함께
스크랩해 확인한다 (todo 2.2 의 2단계 4번 계측 항목).

### DB 확인 쿼리 결과 (같은 쿼리)

```text
order_state	cnt	qty
PENDING	2000	2000

initial_quantity	remaining_quantity
100	0
```

### k6 출력 (진행 줄 생략)

```text
PS D:\dev\IEUM\IEUM_BE> k6 run scripts/k6/create-order.js

     execution: local
        script: scripts/k6/create-order.js
        output: -

     scenarios: (100.00%) 1 scenario, 100 max VUs, 10m30s max duration (incl. graceful stop):
              * order: 10000 iterations shared among 100 VUs (maxDuration: 10m0s, gracefulStop: 30s)


  █ THRESHOLDS

    http_req_duration{name:create-order}
    ✓ 'p(99)<60000' p(99)=662.74ms

    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=164.28ms


  █ TOTAL RESULTS

    checks_total.......: 20000  113.085278/s
    checks_succeeded...: 50.00% 10000 out of 20000
    checks_failed......: 50.00% 10000 out of 20000

    ✗ created 201
      ↳  20% — ✓ 2000 / ✗ 8000
    ✗ sold out 409
      ↳  80% — ✓ 8000 / ✗ 2000

    HTTP
    http_req_duration..............: avg=142.52ms med=82.19ms  p(95)=530.27ms p(99)=652.88ms max=1.88s
      { expected_response:true }...: avg=170.97ms med=78.2ms   p(95)=566.79ms p(99)=657.25ms max=1.88s
      { name:create-order }........: avg=195.31ms med=103.54ms p(95)=577.25ms p(99)=662.74ms max=1.88s
      { name:login }...............: avg=89.72ms  med=77.11ms  p(95)=149.85ms p(99)=164.28ms max=864.29ms
    http_req_failed................: 40.00% 8000 out of 20000
    http_reqs......................: 20000  113.085278/s

    EXECUTION
    iteration_duration.............: avg=196.2ms  med=104.46ms p(95)=578.05ms p(99)=662.95ms max=1.93s
    iterations.....................: 10000  56.542639/s
    vus............................: 100    min=0             max=100
    vus_max........................: 100    min=100           max=100

    NETWORK
    data_received..................: 17 MB  96 kB/s
    data_sent......................: 12 MB  66 kB/s


running (02m56.9s), 000/100 VUs, 10000 complete and 0 interrupted iterations
order ✓ [======================================] 100 VUs  00m20.3s/10m0s  10000/10000 shared iters
```

## 재측정 2 — 계측 포함 (2026-09-16)

09-14 재측정에는 HikariCP 대기와 `order.create.attempts` 지표가 없다. 계측은 2단계 4번에서 뒤늦게 들어갔다.
2단계 라운드 1 은 "지연의 정체는 커넥션 대기" 라고 해석했는데, 그것이 낙관적 락의 비용인지 VU 100 / 풀 20 이라는 실험 조건의
성질인지 가르려면 같은 지표를 naive 에서도 봐야 한다. 그래서 2단계 라운드 1 과 같은 코드에서 `STOCK_STRATEGY=naive` 로 한 번 더 돌렸다.

### 실행 조건 (달라진 것만)

| 항목 | 값 |
|---|---|
| 코드 | 2단계 라운드 1 과 같은 코드 (`dev_be` `0947d91` 이후 문서 커밋만 있음). `OrderCreateRetrier` 가 `OrderService.create` 를 감싸고 `/actuator/prometheus` 가 열려 있다. 작업 트리에 `OptimisticLockStockDeduction` 의 미커밋 변경(라운드 2 용 flush)이 있으나 naive 에서는 그 빈이 등록되지 않아 무관 |
| 스크랩 | 2단계 가이드 4.4 의 `curl` 루프, 0.5초 간격 320회 → `scripts/k6/out/stage1-20260916-233859.prom` |
| 라운드 전 | `reset-loadtest.sql` (이번부터 `AUTO_INCREMENT = 1` 포함) 로 재고 100 · 주문 0 · `version` 0 · AUTO_INCREMENT 1 확인 |

### 결과 요약

| 항목 | 값 (09-16, 계측) | 값 (09-14) | 출처 |
|---|---|---|---|
| 201 (예약 성공) | **1,996** | 2,000 | k6 `created 201`, `order_create_attempts_total{outcome="success"}` 도 1,996 |
| 409 (재고 부족) | 8,004 | 8,000 | k6 `sold out 409` |
| 503 / 500 | 0 / 0 | 0 / 0 | k6 `contention 503` 0, 세 체크 합 10,000 |
| 초과 예약 | **1,896** | 1,900 | |
| DB `PENDING` 주문 수 / 수량 합 | 1,996 / 1,996 | 2,000 / 2,000 | 아래 SQL |
| DB `remaining_quantity` / `version` | 0 / **0** | 0 / (미기록) | 아래 SQL |
| 불변식 `initial = remaining + active` | 깨짐 (100 ≠ 0 + 1,996) | 깨짐 | |
| `order.create.attempts{conflict / exhausted}` | **0 / 0** | (미계측) | Prometheus |
| 성공까지 시도 횟수 | 전부 1회 (`used_max` 1) | (미계측) | `order_create_attempts_used` |
| 롤백된 주문 INSERT (`MAX(id) − COUNT(*)`) | **0** | (미기록) | 아래 SQL |
| `hikaricp.connections.pending` 최대 / `active` 최대 | **80** / 20 | (미계측) | 스크랩 |
| `hikaricp.connections.acquire` 평균 / 최대 | **141ms** (1,411.7s ÷ 10,002) / 712ms | (미계측) | Prometheus |
| 재고 소진까지 | 약 10초 (23:42:04 → 23:42:14) | (미기록) | 스크랩·DB `created_at` |
| 예약 요청 지연 med / p95 / p99 / max | 108ms / 491ms / 633ms / 1.43s | 104ms / 577ms / 663ms / 1.88s | k6 `{ name:create-order }` |
| 로그인 지연 med / p95 / p99 / max | 83ms / 160ms / 172ms / 508ms | 77ms / 150ms / 164ms / 864ms | k6 `{ name:login }` |
| 예약 구간 시간 | 18.9s | 20.3s | k6 진행 줄 `00m18.9s` |
| 예약 처리량 | ≈ 529 req/s (10,000 ÷ 18.9s) | ≈ 493 req/s | 계산 |
| 전체 실행 시간 | 3m04.4s (로그인 setup ≈ 2m45s 포함) | 2m56.9s | k6 `running (03m04.4s)` |

### 해석

**커넥션 대기는 전략과 무관한 실험 조건이다.** `pending` 이 예약 구간 내내 70~80, `active` 는 20 으로 고정이었다.
2단계 라운드 1 의 72~80 / 20 과 같다. VU 100 이 커넥션 20 개를 나눠 쓰는 한 어떤 전략이든 80 개 요청은 항상 풀 앞에서
기다린다. 라운드 1 이 "지연의 정체는 커넥션 대기" 라고 한 것은 낙관적 락의 비용이 아니라 이 부하 조건의 성질이다.
전략 간 비교는 이 줄 위에서 무엇이 달라지는가(실패 시도의 롤백, 락 대기, 재시도) 로 봐야 한다.

`acquire` 평균은 naive 141ms, optimistic 라운드 1 119ms 다. naive 는 재고가 10초 동안 살아 있어 INSERT 를 포함한 긴 트랜잭션이
더 오래 이어졌고, optimistic 은 7초 만에 소진돼 이후 요청이 `SELECT` 한 번으로 끝났기 때문으로 보인다.
naive 의 세 번 측정(09-12 · 09-14 · 09-16)은 201 이 1,996~2,000, p99 633~670ms, 493~529 req/s 범위 안에 있어 실행 간 편차로 본다.

**`version` 이 0 이다.** 1,996 번의 차감이 있었는데 `@Version` 컬럼은 한 번도 오르지 않았다. naive 가 쓰는 JPQL 벌크 UPDATE 는
`@Version` 검사도 증가도 거치지 않는다는 직접 증거다. 엔티티에 `@Version` 이 있어도 더티 체킹 UPDATE 를 타지 않으면 보호받지 못한다.

**재시도 계층은 naive 에서 투명하다.** `conflict` 0, `exhausted` 0, 성공까지 시도 횟수 전부 1회, `success` 카운터가 201 건수와 일치한다.
`OrderCreateRetrier` 를 모든 전략이 공통으로 지나가도 naive 의 결과는 바뀌지 않는다.

**롤백된 INSERT 가 0 이다.** naive 에는 실패하는 트랜잭션이 없다. 2단계의 "실패 시도의 DB 비용" (라운드 1 에서 ≈ 2,048) 을 비교할 기준선이다.

### DB 확인 쿼리 결과 (같은 쿼리 + 롤백 수)

```text
order_state	cnt	qty
PENDING	1996	1996

initial_quantity	remaining_quantity	version
100	0	0

max_id	cnt	rolled_back_inserts
1996	1996	0

MIN(created_at)	MAX(created_at)
2026-09-16 23:42:03.980956	2026-09-16 23:42:13.753184
```

### 스크랩 타임라인 (초당 `pending` 최대 / `success` 누적)

```text
23:42:04  70 /   10
23:42:05  80 /  273
23:42:06  80 /  400
23:42:07  80 /  692
23:42:08  79 /  838
23:42:09  80 / 1131
23:42:10  80 / 1280
23:42:11  79 / 1576
23:42:12  79 / 1716
23:42:13  79 / 1879
23:42:14  75 / 1996   ← 재고 0, 이후 success 변화 없음
23:42:15  74
23:42:16  77
23:42:17  80
23:42:18  80
23:42:19  80
23:42:20  80
23:42:21  79          ← 예약 구간 끝
```

재고가 0 이 된 뒤에도 `pending` 이 80 근처를 유지한다. 409 로 끝나는 짧은 트랜잭션도 커넥션은 잡아야 하므로 풀 앞의 줄은 사라지지 않는다.

### k6 출력 (진행 줄 생략)

```text
PS D:\dev\IEUM\IEUM_BE> k6 run scripts/k6/create-order.js

     execution: local
        script: scripts/k6/create-order.js
        output: -

     scenarios: (100.00%) 1 scenario, 100 max VUs, 10m30s max duration (incl. graceful stop):
              * order: 10000 iterations shared among 100 VUs (maxDuration: 10m0s, gracefulStop: 30s)


  █ THRESHOLDS

    http_req_duration{name:create-order}
    ✓ 'p(99)<60000' p(99)=633.45ms

    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=172.35ms


  █ TOTAL RESULTS

    checks_total.......: 30000  162.663179/s
    checks_succeeded...: 33.33% 10000 out of 30000
    checks_failed......: 66.66% 20000 out of 30000

    ✗ created 201
      ↳  19% — ✓ 1996 / ✗ 8004
    ✗ sold out 409
      ↳  80% — ✓ 8004 / ✗ 1996
    ✗ contention 503
      ↳  0% — ✓ 0 / ✗ 10000

    HTTP
    http_req_duration..............: avg=137.76ms med=88.63ms  p(95)=461.75ms p(99)=548.32ms max=1.43s
      { expected_response:true }...: avg=159.47ms med=85.27ms  p(95)=483.6ms  p(99)=558.83ms max=1.43s
      { name:create-order }........: avg=180.78ms med=108.46ms p(95)=491.05ms p(99)=633.45ms max=1.43s
      { name:login }...............: avg=94.74ms  med=83.26ms  p(95)=159.87ms p(99)=172.35ms max=508.06ms
    http_req_failed................: 40.02% 8004 out of 20000
    http_reqs......................: 20000  108.44212/s

    EXECUTION
    iteration_duration.............: avg=181.53ms med=109.04ms p(95)=491.09ms p(99)=637.16ms max=1.59s
    iterations.....................: 10000  54.22106/s
    vus............................: 100    min=0             max=100
    vus_max........................: 100    min=100           max=100

    NETWORK
    data_received..................: 17 MB  92 kB/s
    data_sent......................: 12 MB  64 kB/s


running (03m04.4s), 000/100 VUs, 10000 complete and 0 interrupted iterations
order ✓ [======================================] 100 VUs  00m18.9s/10m0s  10000/10000 shared iters
```

## 다음 라운드 전

`IEUM_BE/scripts/sql/reset-loadtest.sql` 로 재고·주문을 초기화한다 (09-16 재측정 2 뒤 실행해 둔 상태, AUTO_INCREMENT 도 1).
2단계부터는 `.env` 에 `SQL_LOG_LEVEL=warn`, `SQL_BIND_LOG_LEVEL=off` 를 넣고 두 서버를 재기동한 뒤 실행하며,
지연 비교는 09-14 재측정 값과, 커넥션 대기·롤백 수 비교는 09-16 재측정 2 값과 한다.
