# 부하 테스트 기록 — 2단계 `@Version` 낙관적 락 + 재시도 (optimistic)

- 날짜: 2026-09-14
- 목적: `@Version` 낙관적 락과 재시도 계층으로 초과 예약이 사라지는지, 그 대가로 무엇이 남는지 측정 ([ADR-0003](../adr/0003-stock-deduction-concurrency.md) 2단계)
- 스크립트: `IEUM_BE/scripts/k6/create-order.js` (`contention 503` 체크 추가된 버전)
- 시드: `IEUM_BE/scripts/sql/seed-loadtest.sql` (소비자 10,000, 재고 100 상품 1개)
- 비교 기준: [1단계 재측정 (09-14, 로그 끔)](./2026-09-12-stage1-naive.md#재측정--sql-로그-끔-2026-09-14) · 커넥션 대기·롤백 수는 [1단계 재측정 2 (09-16, 계측 포함)](./2026-09-12-stage1-naive.md#재측정-2--계측-포함-2026-09-16)

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

| | 1. naive (09-14) | 1. naive (09-16, 계측) | 2. optimistic 라운드 1 |
|---|---|---|---|
| 201 | 2,000 | 1,996 | **100** |
| 초과 예약 | 1,900 | 1,896 | **0** |
| 오류 (500) | 0 | 0 | **1,926** |
| p99 | 663ms | 633ms | 716ms |
| 중앙값 | 104ms | 108ms | 114ms |
| 처리량 | ≈ 493 req/s | ≈ 529 req/s | ≈ 588 req/s |
| `pending` 최대 / `active` 최대 | (미계측) | 80 / 20 | 80 / 20 |
| `acquire` 평균 / 최대 | (미계측) | 141ms / 712ms | 119ms / 963ms |
| 롤백된 주문 INSERT | (미기록) | 0 | ≈ 2,048 |
| 재고 소진까지 | (미기록) | 약 10초 | 약 7초 |

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

09-16 에 naive 를 같은 계측으로 다시 돌린 결과 `pending` 최대 80, `active` 20, `acquire` 평균 141ms 로 같은 그림이었다
([1단계 재측정 2](./2026-09-12-stage1-naive.md#재측정-2--계측-포함-2026-09-16)). 즉 풀 앞의 줄은 낙관적 락의 비용이 아니라
VU 100 / 풀 20 이라는 실험 조건의 성질이고, 전략 간 차이는 그 줄 위에서 무엇이 달라지는가(롤백된 INSERT 0 대 ≈ 2,048, 데드락, 재시도) 에 있다.

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

## 라운드 2 — flush 순서 변경 + 데드락 재시도 (2026-09-17)

라운드 1 의 "다음 라운드에서 고칠 것" 세 가지를 적용하고 같은 조건으로 돌렸다.

### 실행 조건 (라운드 1 과 달라진 것만)

| 항목 | 값 |
|---|---|
| 코드 | `dev_be` `689ac1b` — `OptimisticLockStockDeduction.deduct` 가 `decreaseQuantity` 직후 `items.flush()` (`b25ad9e`), `OrderCreateRetrier` 가 `CannotAcquireLockException` 도 재시도하고 `outcome=deadlock` 카운터 (`237f456`), `ApiExceptionAdvice` 503 핸들러가 두 예외를 받음 (`8e3c2f1`) |
| 라운드 전 | `reset-loadtest.sql` (AUTO_INCREMENT 초기화 포함) → 재고 100 · 주문 0 · `version` 0 · AUTO_INCREMENT 1 |
| 스크랩 | 0.5초 간격 369회 → `scripts/k6/out/stage2-20260917-001603.prom` |
| MySQL | 09-16 23:35 재기동 이후 재시작 없음 (`Uptime` 2,953s). 이 사이 데드락이 없으면 `SHOW ENGINE INNODB STATUS` 에 `LATEST DETECTED DEADLOCK` 절이 아예 없다 |

### 결과 요약

| 항목 | 값 (라운드 2) | 값 (라운드 1) | 출처 |
|---|---|---|---|
| 201 (예약 성공) | **100** | 100 | k6 `created 201` |
| 409 (재고 부족·중복) | 9,326 | 7,970 | k6 `sold out 409` |
| 503 (재시도 소진) | **574** | 4 | k6 `contention 503` |
| 500 (데드락) | **0** | 1,926 | 세 체크 합 10,000 |
| 초과 예약 | **0** | 0 | |
| DB `PENDING` 주문 수 / 수량 합 | 100 / 100 | 100 / 100 | 아래 SQL |
| DB `remaining_quantity` / `version` | 0 / 100 | 0 / 100 | 아래 SQL |
| 불변식 `initial = remaining + active` | **성립** (100 = 0 + 100) | 성립 | |
| `order.create.attempts{success / conflict / exhausted / deadlock}` | 100 / **1,894** / 574 / **0** | 100 / 137 / 4 / (없음) | Prometheus |
| 성공까지 시도 횟수 (1회 / 2회 / 3회) | 59 / 18 / 23 | 91 / 4 / 5 | `order_create_attempts_used_bucket` |
| 충돌 분해 — 성공 쪽 / 소진 쪽 / 재시도에서 409 로 끝남 | 64 (18×1 + 23×2) / 1,722 (574×3) / 108 | 14 / 12 / 111 | 계산 |
| 커넥션 획득 횟수 | 11,322 = 10,000 + 재시도 1,320 (1,894 − 574) + 기동 2 | 10,135 | `acquire_seconds_count` |
| 롤백된 주문 INSERT (`MAX(id) − COUNT(*)`) | **0** | ≈ 2,048 | 아래 SQL |
| `hikaricp.connections.pending` 최대 / `active` 최대 | 79 / 20 | 80 / 20 | 스크랩 |
| `hikaricp.connections.acquire` 평균 / 최대 | **88ms** (999.7s ÷ 11,322) / 1.09s | 119ms / 963ms | Prometheus |
| 예약 요청 지연 med / p95 / p99 / max | 93ms / 491ms / **790ms** / 1.82s | 114ms / 381ms / 716ms / 1.55s | k6 `{ name:create-order }` |
| 로그인 지연 med / p95 / p99 / max | 93ms / 180ms / 195ms / 510ms | 75ms / 146ms / 157ms / 547ms | k6 `{ name:login }` |
| 예약 구간 시간 | 13.9s | 17.0s | k6 진행 줄 `00m13.9s` |
| 예약 처리량 | ≈ 719 req/s (10,000 ÷ 13.9s) | ≈ 588 req/s | 계산 |
| 재고 소진까지 | 약 5초 (00:19:15 → 00:19:20) | 약 7초 | 스크랩·DB `created_at` |
| InnoDB `LATEST DETECTED DEADLOCK` | **절 없음** (09-16 23:35 재기동 이후 데드락 0) | 09-14 23:14 | `SHOW ENGINE INNODB STATUS` |

세 라운드를 나란히 놓으면:

| | 1. naive (09-16, 계측) | 2. optimistic 라운드 1 | 2. optimistic 라운드 2 |
|---|---|---|---|
| 201 | 1,996 | **100** | **100** |
| 초과 예약 | 1,896 | 0 | 0 |
| 500 | 0 | 1,926 | **0** |
| 503 (재고가 있는데 답을 못 줌) | 0 | 4 | **574** (5.7%) |
| 충돌 시도 | 0 | 137 | 1,894 |
| 롤백된 주문 INSERT | 0 | ≈ 2,048 | **0** |
| p99 / 중앙값 | 633ms / 108ms | 716ms / 114ms | 790ms / 93ms |
| 처리량 | ≈ 529 req/s | ≈ 588 req/s | ≈ 719 req/s |
| `pending` 최대 / `acquire` 평균 | 80 / 141ms | 80 / 119ms | 79 / 88ms |
| 재고 소진까지 | 약 10초 | 약 7초 | 약 5초 |

### 해석

**데드락은 사라졌다.** 500 이 0, `deadlock` 카운터 0, InnoDB 상태에 데드락 절 자체가 없다. flush 로 `UPDATE stores_items`
가 `INSERT users_orders` 보다 먼저 나가 X 락을 선점하므로, 뒤따르는 FK 검사 S 락은 같은 트랜잭션 안에서 바로 허용된다.
9.3 의 데드락 재시도는 한 번도 타지 않았고, 0 이 찍힌 것이 방어선이 계측되고 있다는 증거다.

**실패 시도의 DB 비용이 INSERT 한 개만큼 줄었다.** 롤백된 주문 INSERT 가 ≈ 2,048 에서 **0** 이 됐다. 라운드 1 에서는
INSERT 가 먼저 나가고 커밋 때 version 검사에 걸렸으므로 실패마다 INSERT 와 auto-increment 가 낭비됐다. 라운드 2 에서는
version 검사가 flush 시점, 즉 INSERT 전에 일어나므로 실패한 시도는 `SELECT` 세 번과 실패한 `UPDATE` 만 남기고 롤백된다.
`users_orders` 의 `MAX(id)` 가 정확히 100 이다.

**그 대가로 충돌과 503 이 크게 늘었다.** 라운드 1 에서 데드락으로 일찍 죽던 1,926 건이 이제 version 검사까지 간다.
충돌 137 → 1,894, 503 은 4 → 574 (전체의 5.7%). 574 건은 모두 재고가 남아 있을 때 났다. 503 은 세 번 연속 version
불일치여야 하고, version 불일치는 `remaining ≥ quantity` 를 읽은 뒤에만 일어나므로 재고 0 을 읽은 요청은 409 로 끝나기 때문이다.
스크랩에서도 `exhausted` 는 `success` 가 100 에 닿는 00:19:20 에 함께 멈췄다.
**이 574 가 "재고가 있는데 답을 못 준 요청" 의 진짜 값이다.** 라운드 1 의 4 는 데드락이 경쟁자를 미리 죽여 준 결과였다.

**flush 이후 구간은 비관적으로 동작한다.** flush 에서 version 검사를 통과한 트랜잭션은 커밋까지 `stores_items` 행의 X 락을
쥔다. 뒤따르는 트랜잭션의 UPDATE 는 그 락에서 대기했다가 커밋 후 `WHERE version = ?` 가 0 건이 되어 실패한다.
재고 한 줄을 두고 UPDATE 가 직렬화되므로 재고 100 이 5초 만에 소진됐고 (라운드 1 은 7초), 락 대기가 p99 를 716 → 790ms 로
밀어 올렸다. 중앙값이 114 → 93ms 로 내려간 것은 소진 후 409 로 끝나는 짧은 요청이 전체의 93% 가 됐기 때문이다.

**커넥션 대기는 여전히 실험 조건이다.** `pending` 최대 79, `active` 20 은 세 라운드 모두 같다. `acquire` 평균이 88ms 로
내려간 것은 실패 시도가 INSERT 없이 빨리 끝나 커넥션 회전이 빨라진 결과로 보인다. 처리량 719 req/s 도 같은 이유다.

**재시도 계층의 상한 3 은 이 경쟁 강도에는 낮다.** 성공 100 중 41 이 2~3 번째 시도에서 났고, 574 는 세 번 모두 졌다.
백오프 `[0, 10ms × 시도]` 는 X 락 보유 시간(flush → INSERT → commit) 보다 짧아, 재시도가 같은 락 대기 줄에 다시 서는
경우가 많았을 것이다. 상한을 올리면 503 은 줄고 p99·`pending` 은 오른다 (가이드 6.10 의 선택 라운드). 그 트레이드오프를 재기
전에, 3단계 Redis 가 이 직렬화 자체를 DB 밖으로 옮겼을 때 503 과 p99 가 어떻게 되는지를 먼저 본다.

### `@Version` 으로 해결되는 것과 남는 것 (라운드 2 기준)

- 해결: 초과 예약 0 (201 정확히 100, `version` 정확히 100, 불변식 성립). 데드락 0. 실패 시도의 INSERT 낭비 0
- 남는 것
  - 재고가 있는데 답을 못 준 요청 574 (5.7%). 상한 3 에서의 값이며, 상한과 백오프의 함수
  - 실패 시도의 DB 비용: 충돌 1,894 회 × (`SELECT` 3 + 실패한 `UPDATE` + 롤백). 커넥션 획득이 10,000 이 아니라 11,322
  - 재고 행 밖의 불변식: 중복 활성 예약 검사는 여전히 DB 조회 기반이라 동시 요청 사이의 틈이 남아 있다 (3단계에서 Lua 안으로)
  - 처리량 상한: 재고 한 줄의 X 락에 UPDATE 가 직렬화된다. flush 를 앞당긴 뒤로는 낙관적 락이라기보다 "짧은 비관적 락 + version 검증" 에 가깝다

### DB 확인 쿼리와 결과

```text
order_state	cnt	qty
PENDING	100	100

initial_quantity	remaining_quantity	version
100	0	100

max_id	cnt	rolled_back_inserts
100	100	0

MIN(created_at)	MAX(created_at)
2026-09-17 00:19:15.752515	2026-09-17 00:19:19.628472
```

`SHOW ENGINE INNODB STATUS\G` 에 `LATEST DETECTED DEADLOCK` 절이 없다. 이 절은 서버 기동 후 데드락이 한 번이라도 있어야
생기며, MySQL 은 09-16 23:35 (naive 재측정 2 직전) 에 기동됐다. 즉 naive 재측정 2 와 이번 라운드 모두 데드락 0 이다.

### 스크랩 타임라인 (초당 `pending` 최대 | success / conflict / exhausted / deadlock 누적)

```text
00:19:15   0 |    0 /    0 /   0 / 0   ← 예약 시작 (첫 주문 created_at 00:19:15.75)
00:19:16  77 |   23 /  412 /  96 / 0
00:19:17  78 |   43 /  811 / 230 / 0
00:19:18  78 |   63 / 1184 / 356 / 0
00:19:19  78 |   87 / 1643 / 499 / 0
00:19:20  73 |  100 / 1894 / 574 / 0   ← 재고 0. 이후 세 카운터 모두 변화 없음
00:19:21  74
00:19:22  74
00:19:23  75
00:19:24  75
00:19:25  79
00:19:26  79
00:19:27  77
00:19:28  77
00:19:29   0 |                          ← 예약 구간 끝 (13.9s)
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
    ✓ 'p(99)<60000' p(99)=790.03ms

    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=195.1ms


  █ TOTAL RESULTS

    checks_total.......: 30000  150.298868/s
    checks_succeeded...: 33.33% 10000 out of 30000
    checks_failed......: 66.66% 20000 out of 30000

    ✗ created 201
      ↳  1% — ✓ 100 / ✗ 9900
    ✗ sold out 409
      ↳  93% — ✓ 9326 / ✗ 674
    ✗ contention 503
      ↳  5% — ✓ 574 / ✗ 9426

    HTTP
    http_req_duration..............: avg=118.54ms med=93.34ms p(95)=201.64ms p(99)=607.74ms max=1.82s
      { expected_response:true }...: avg=109.12ms med=93.38ms p(95)=180.72ms p(99)=207.22ms max=1.57s
      { name:create-order }........: avg=130.62ms med=93.43ms p(95)=490.67ms p(99)=790.03ms max=1.82s
      { name:login }...............: avg=106.45ms med=93.33ms p(95)=179.55ms p(99)=195.1ms  max=510.14ms
    http_req_failed................: 49.50% 9900 out of 20000
    http_reqs......................: 20000  100.199245/s

    EXECUTION
    iteration_duration.............: avg=131.44ms med=93.94ms p(95)=491.46ms p(99)=790.92ms max=1.83s
    iterations.....................: 10000  50.099623/s
    vus............................: 100    min=0             max=100
    vus_max........................: 100    min=100           max=100

    NETWORK
    data_received..................: 17 MB  85 kB/s
    data_sent......................: 12 MB  59 kB/s


running (03m19.6s), 000/100 VUs, 10000 complete and 0 interrupted iterations
order ✓ [======================================] 100 VUs  00m13.9s/10m0s  10000/10000 shared iters
```

### Prometheus 최종 스냅샷

```text
order_create_attempts_total{outcome="conflict"} 1894.0
order_create_attempts_total{outcome="deadlock"} 0.0
order_create_attempts_total{outcome="exhausted"} 574.0
order_create_attempts_total{outcome="success"} 100.0
order_create_attempts_used_bucket{le="1.0"} 59
order_create_attempts_used_bucket{le="2.0"} 77
order_create_attempts_used_bucket{le="3.0"} 100
order_create_attempts_used_count 100
order_create_attempts_used_sum 164.0
hikaricp_connections_acquire_seconds_count{pool="IeumHikariPool"} 11322
hikaricp_connections_acquire_seconds_sum{pool="IeumHikariPool"} 999.6957872
hikaricp_connections_acquire_seconds_max{pool="IeumHikariPool"} 1.0872998
```

## 다음 라운드 전

`reset-loadtest.sql` 로 초기화해 둔 상태다 (재고 100 · 주문 0 · `version` 0 · AUTO_INCREMENT 1).
2단계는 라운드 2 로 측정을 마친다. 남은 선택지는 두 가지이며 순서는 todo 에서 정한다.

- (선택) 조건부 UPDATE 한 문장 — `@Version` 도 재시도도 없는 중간 데이터 포인트. 503 이 0 이 되는 대신 무엇이 남는지
- (선택) `STOCK_RETRY_MAX_ATTEMPTS=10` — 503 574 가 얼마나 줄고 p99·`pending` 이 얼마나 오르는지 한 줄
- 3단계 Redis Lua — 재고 직렬화를 DB 밖으로 옮겼을 때 503 과 p99, `pending` 이 어떻게 되는지
