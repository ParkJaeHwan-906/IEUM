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

SQL 로그가 켜진 채 실행되어 지연 수치(p99, 처리량)에 로그 출력 비용이 섞여 있다. **초과 예약 건수는 이 설정과 무관하게 유효하지만, 2·3단계와 지연을 비교할 때는 같은 로그 설정으로 다시 돌린 값을 써야 한다.**

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

## 다음 라운드 전

`IEUM_BE/scripts/sql/reset-loadtest.sql` 로 재고·주문을 초기화한다. 2단계부터는 `.env` 에 `SQL_LOG_LEVEL=warn`, `SQL_BIND_LOG_LEVEL=off` 를 넣고 두 서버를 재기동한 뒤 실행하며, 지연 비교를 위해 1단계도 같은 설정으로 한 번 더 돌려 이 문서에 덧붙인다.
