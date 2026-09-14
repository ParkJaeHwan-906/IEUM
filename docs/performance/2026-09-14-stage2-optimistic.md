# 부하 테스트 기록 — 2단계 `@Version` 낙관적 락 + 재시도 (optimistic)

- 날짜: 2026-09-14
- 목적: `@Version` 낙관적 락과 재시도 계층으로 초과 예약이 사라지는지, 그 대가로 무엇이 남는지 측정 ([ADR-0003](../adr/0003-stock-deduction-concurrency.md) 2단계)
- 스크립트: `IEUM_BE/scripts/k6/create-order.js` (`contention 503` 체크 추가된 버전)
- 시드: `IEUM_BE/scripts/sql/seed-loadtest.sql` (소비자 10,000, 재고 100 상품 1개)
- 비교 기준: [1단계 재측정 (09-14, 로그 끔)](./2026-09-12-stage1-naive.md#재측정--sql-로그-끔-2026-09-14)

## 라운드 1 — 재시도 계층만 (데드락 발견)

### 실행 조건

| 항목 | 값 |
|---|---|
| `STOCK_STRATEGY` | `optimistic` |
| `STOCK_RETRY_MAX_ATTEMPTS` / `STOCK_RETRY_BACKOFF` | 3 / `PT0.01S` (대기는 `[0, 10ms × 시도]` 균등 난수) |
| VU / 총 요청 | 100 / 10,000 (소비자 1인당 1건, `quantity` 1) |
| `SQL_LOG_LEVEL` / `SQL_BIND_LOG_LEVEL` | `warn` / `off` |
| 기동 방식 | `bootJar` → `IEUM_BE` 에서 `java -jar`, 환경변수로 전략·로그 지정 (1단계 재측정과 동일). IntelliJ 실행은 `-XX:TieredStopAtLevel=1` 이 붙어 지연 비교에 쓰지 않음 |
| API 서버 HikariCP `maximum-pool-size` | 20 (기본값) |
| 코드 | `dev_be` `0947d91` — `OrderCreateRetrier`, 503 + `Retry-After`, Prometheus 계측 포함 |
| 라운드 전 | `reset-loadtest.sql` → 재고 100 · 주문 0 · `version` 0, `/actuator/prometheus` 카운터 0 확인 |
| 지표 수집 | `/actuator/prometheus` 를 0.5초마다 `curl` 로 스크랩 (`scripts/k6/out/stage2-20260914-231203.prom`, 저장소 제외) |
| 실행 환경 | 1단계와 동일 (Ryzen 5 5600, 16GB, Windows 11, Docker Desktop, k6 v2.2.0 호스트 실행) |

### 결과 요약

| 항목 | 값 | 출처 |
|---|---|---|
| 201 (예약 성공) | **100** (정확히 재고만큼) | k6 `created 201` |
| 409 (재고 부족·중복) | 7,970 | k6 `sold out 409` |
| 503 (재시도 소진) | 4 | k6 `contention 503` |
| **500 (데드락)** | **1,926** (세 체크 합 8,074 → 나머지) | API 로그 `CannotAcquireLockException` 1,926건 |
| 초과 예약 | **0** | |
| DB `PENDING` 주문 수 / 수량 합 | 100 / 100 | 아래 SQL |
| DB `remaining_quantity` / `version` | 0 / 100 | 아래 SQL |
| 불변식 `initial = remaining + active` | **성립** (100 = 0 + 100) | |
| `order.create.attempts{success / conflict / exhausted}` | 100 / 137 / 4 | Prometheus |
| 성공까지 시도 횟수 (1회 / 2회 / 3회) | 91 / 4 / 5 | `order_create_attempts_used_bucket` |
| 충돌 후 재시도에서 409 로 끝난 요청 | 111 (137 − 성공 쪽 14 − 소진 쪽 12) | 계산 |
| 롤백된 주문 INSERT | ≈ 2,048 (`MAX(id) − COUNT(*)` 에서 이전 라운드 몫 3,996 제외) ≈ 충돌 137 + 데드락 1,926 | 아래 SQL |
| `hikaricp.connections.pending` 최대 / `active` 최대 | **80** / 20 | 스크랩 |
| `hikaricp.connections.acquire` 평균 / 최대 | **119ms** (1,205.2s ÷ 10,135) / 963ms | Prometheus |
| 예약 요청 지연 med / p95 / p99 / max | 114ms / 381ms / 716ms / 1.55s | k6 `{ name:create-order }` |
| 로그인 지연 med / p95 / p99 / max | 75ms / 146ms / 157ms / 547ms | k6 `{ name:login }` |
| 예약 구간 시간 | 17.0s | k6 진행 줄 `00m17.0s` |
| 예약 처리량 | ≈ 588 req/s (10,000 ÷ 17.0s) | 계산 |
| 재고 소진까지 | 약 7초 (23:14:43 → 23:14:50) | 스크랩·API 로그 |

1단계 재측정과 나란히 놓으면:

| | 1. naive (09-14) | 2. optimistic 라운드 1 |
|---|---|---|
| 201 | 2,000 | **100** |
| 초과 예약 | 1,900 | **0** |
| 오류 (500) | 0 | **1,926** |
| p99 | 663ms | 716ms |
| 중앙값 | 104ms | 114ms |
| 처리량 | ≈ 493 req/s | ≈ 588 req/s |

### 해석

**초과 예약은 사라졌다.** 201 이 정확히 100, `version` 이 정확히 100 (성공한 UPDATE 수), 불변식이 성립한다.
`@Version` 은 맡은 일을 했다.

**그러나 19% 가 500 을 받았다.** 원인은 낙관적 락 충돌이 아니라 **MySQL 데드락** (ErrorCode 1213, SQLState 40001) 이다.
`SHOW ENGINE INNODB STATUS` 의 LATEST DETECTED DEADLOCK:

```text
(1) HOLDS:   index PRIMARY of table `ieum`.`stores_items` lock mode S locks rec but not gap
(1) WAITING: index PRIMARY of table `ieum`.`stores_items` lock_mode X locks rec but not gap waiting
(2) HOLDS:   index PRIMARY of table `ieum`.`stores_items` lock mode S locks rec but not gap
(2) WAITING: index PRIMARY of table `ieum`.`stores_items` lock_mode X locks rec but not gap waiting
*** WE ROLL BACK TRANSACTION (2)
```

두 트랜잭션 모두 `stores_items` 행에 **S 락을 쥔 채 X 락을 기다린다**. 순서는 다음과 같다.

1. `OrderService.create` 가 `stock.deduct` 를 부르지만, 낙관적 락 구현은 엔티티 필드만 바꾸고 UPDATE 는 flush 때로 미룬다
2. `orders.save` 가 IDENTITY 전략이라 `INSERT users_orders` 를 **즉시** 실행한다. InnoDB 는 FK(`store_item_id`) 검사를 위해
   부모 행 `stores_items` 에 **S 락**을 잡는다
3. 커밋 직전 flush 에서 `UPDATE stores_items ... WHERE id = ? AND version = ?` 가 나가며 같은 행에 **X 락**을 요구한다
4. 다른 트랜잭션도 2 까지 와 있으면 서로의 S 락 때문에 둘 다 X 락을 못 받는다 → InnoDB 가 하나를 희생시킨다

즉 SQL 실행 순서가 `INSERT(자식, S 락) → UPDATE(부모, X 락)` 이라서 생기는 고전적인 FK 데드락이다.
1단계 naive 에서 데드락이 없었던 이유도 여기 있다. naive 는 JPQL 벌크 UPDATE 를 `deduct()` 안에서 즉시 실행하므로
순서가 `UPDATE(X 락) → INSERT(S 락)` 이고, 같은 트랜잭션이 이미 X 를 쥐고 있어 S 는 바로 허용된다.

재고가 남아 있던 7초 동안 UPDATE 까지 도달한 트랜잭션은 2,163건 (성공 100 + 충돌 137 + 데드락 1,926) 인데 그중 89% 가
데드락으로 죽었다. `@Version` 충돌(137)보다 데드락(1,926)이 14배 많다. 재시도 계층은 `OptimisticLockingFailureException`
만 잡으므로 `CannotAcquireLockException` 은 그대로 `ApiExceptionAdvice` 를 지나쳐 500 이 됐다.

**지연의 정체는 커넥션 대기다.** `hikaricp.connections.pending` 이 예약 구간 내내 72~80, `active` 는 20 으로 고정이었다.
VU 100 중 80 은 항상 커넥션을 기다리고 있었다는 뜻이다. `acquire` 평균 119ms 는 예약 요청 중앙값 114ms 와 거의 같다.
즉 요청 시간의 대부분이 DB 가 아니라 **풀 앞의 줄**이다. 처리량이 1단계보다 오히려 높은 것(588 vs 493)은 재고가 7초 만에
소진된 뒤 나머지 요청이 INSERT 없이 `SELECT` 한 번으로 409 를 받아 트랜잭션이 짧아졌기 때문이다.

**재시도 계층 자체는 설계대로 움직였다.** 충돌 137건 중 14건은 2~3번째에 성공, 4건은 상한 소진(503), 111건은 재시도에서
재고 0 을 읽고 409 로 끝났다 (재고 없는데 반복하지 않음). 503 은 4건뿐이라 "재고가 있는데 답을 못 준 요청" 은
낙관적 락 쪽에서는 미미했고, 문제는 전부 데드락 쪽에서 났다.

### 다음 라운드에서 고칠 것

1. **SQL 순서를 바꾼다** — `OptimisticLockStockDeduction.deduct` 에서 `decreaseQuantity` 뒤에 flush 해 UPDATE 가 INSERT 보다
   먼저 나가게 한다. X 락을 먼저 잡으면 이후 FK 검사의 S 락은 같은 트랜잭션 안이라 충돌하지 않는다.
   flush 시점의 `OptimisticLockException` 은 리포지토리 프록시의 예외 번역을 거쳐 `ObjectOptimisticLockingFailureException`
   으로 나오므로 재시도 계층이 그대로 잡는다
2. **데드락 희생자도 재시도 대상에 넣는다** — `CannotAcquireLockException` 은 MySQL 이 "try restarting transaction" 이라고
   말하는 일시적 실패다. 1 을 적용하면 사라져야 하지만, 방어선으로 잡고 카운터에 `outcome=deadlock` 태그를 두면
   재발 여부가 지표로 보인다
3. `reset-loadtest.sql` 에 `ALTER TABLE users_orders AUTO_INCREMENT = 1` 을 넣어 `MAX(id) − COUNT(*)` 가 그 라운드의
   롤백 수를 바로 가리키게 한다

### DB 확인 쿼리와 결과

```sql
SELECT order_state, COUNT(*) AS cnt, SUM(quantity) AS qty
  FROM users_orders o JOIN stores_items i ON i.id = o.store_item_id
 WHERE i.uid = '33333333-3333-3333-3333-333333333333' GROUP BY order_state;
SELECT initial_quantity, remaining_quantity, version
  FROM stores_items WHERE uid = '33333333-3333-3333-3333-333333333333';
SELECT MAX(id) AS max_id, COUNT(*) AS cnt, MAX(id) - COUNT(*) AS rolled_back_inserts FROM users_orders;
```

```text
order_state	cnt	qty
PENDING	100	100

initial_quantity	remaining_quantity	version
100	0	100

max_id	cnt	rolled_back_inserts
6144	100	6044
```

`rolled_back_inserts` 6,044 에는 이전 두 라운드(1,996 + 2,000)가 소비한 auto-increment 가 포함돼 있다. 이번 라운드 몫은 약 2,048.

### 데드락 발생 시각 분포 (API 로그 ERROR, 초당)

```text
23:14:43  104
23:14:44  244
23:14:45  150
23:14:46  284
23:14:47  304
23:14:48  316
23:14:49  404
23:14:50  120   ← 이 초에 재고 0, 이후 데드락 없음
```

### 스크랩 타임라인 (success / conflict / exhausted 누적)

```text
23:14:44  5 / 6 / 0
23:14:45  19 / 23 / 0
23:14:46  33 / 41 / 2
23:14:47  50 / 67 / 2
23:14:48  68 / 89 / 3
23:14:49  87 / 116 / 4
23:14:50  100 / 137 / 4   ← 이후 변화 없음
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
    ✓ 'p(99)<60000' p(99)=715.98ms

    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=157.18ms


  █ TOTAL RESULTS

    checks_total.......: 30000  179.285644/s
    checks_succeeded...: 26.91% 8074 out of 30000
    checks_failed......: 73.08% 21926 out of 30000

    ✗ created 201
      ↳  1% — ✓ 100 / ✗ 9900
    ✗ sold out 409
      ↳  79% — ✓ 7970 / ✗ 2030
    ✗ contention 503
      ↳  0% — ✓ 4 / ✗ 9996

    HTTP
    http_req_duration..............: avg=122.34ms med=90.04ms  p(95)=313.08ms p(99)=537.93ms max=1.55s
      { expected_response:true }...: avg=89.04ms  med=75.32ms  p(95)=146.7ms  p(99)=208.01ms max=1.44s
      { name:create-order }........: avg=158.6ms  med=114.35ms p(95)=381.05ms p(99)=715.98ms max=1.55s
      { name:login }...............: avg=86.09ms  med=75.28ms  p(95)=145.6ms  p(99)=157.18ms max=547.13ms
    http_req_failed................: 49.50% 9900 out of 20000
    http_reqs......................: 20000  119.523763/s

    EXECUTION
    iteration_duration.............: avg=160.15ms med=115.25ms p(95)=387.45ms p(99)=719.9ms  max=1.56s
    iterations.....................: 10000  59.761881/s
    vus............................: 100    min=0             max=100
    vus_max........................: 100    min=100           max=100

    NETWORK
    data_received..................: 17 MB  101 kB/s
    data_sent......................: 12 MB  70 kB/s


order ✓ [======================================] 100 VUs  00m17.0s/10m0s  10000/10000 shared iters
```

### Prometheus 최종 스냅샷

```text
order_create_attempts_total{outcome="conflict"} 137.0
order_create_attempts_total{outcome="exhausted"} 4.0
order_create_attempts_total{outcome="success"} 100.0
order_create_attempts_used_bucket{le="1.0"} 91
order_create_attempts_used_bucket{le="2.0"} 95
order_create_attempts_used_bucket{le="3.0"} 100
order_create_attempts_used_count 100
order_create_attempts_used_sum 114.0
hikaricp_connections_acquire_seconds_count{pool="IeumHikariPool"} 10135
hikaricp_connections_acquire_seconds_sum{pool="IeumHikariPool"} 1205.1835425
hikaricp_connections_acquire_seconds_max{pool="IeumHikariPool"} 0.9629876
```

## 다음 라운드 전

`reset-loadtest.sql` 로 초기화해 둔 상태다. 라운드 2 는 위 "다음 라운드에서 고칠 것" 1·2 를 적용한 뒤 같은 조건으로 돌리고
이 문서에 이어서 기록한다. 기대: 500 이 0, 충돌·503 은 늘 수 있음 (데드락으로 죽던 트랜잭션이 이제 `@Version` 검사까지 가므로).
