# 부하 테스트 기록 — `synchronized` (JVM 모니터) 두 라운드

3단계 Redis Lua 와 나란히 놓기 위한 비교 데이터 포인트다. "가장 먼저 떠오르는 방법" 인 `synchronized` 를 두 범위로 걸어
같은 시나리오(재고 100 / 요청 10,000 / VU 100) 로 측정했다. 비교 기준은 같은 날 앞서 측정한 [3단계 Redis](./2026-09-23-stage3-redis.md) 와
[(선택) 조건부 UPDATE](./2026-09-17-stage2-conditional-update.md).

| 라운드 | `STOCK_SYNC_SCOPE` | 락의 범위 | 질문 |
|---|---|---|---|
| S2 | `create` | `OrderCreateRetrier` 가 `orderService.create` 호출 전체(트랜잭션 시작 → 커밋)를 모니터로 감싼다 | 정합성이 맞는 `synchronized` 의 비용은 얼마인가 |
| S1 | `deduct` | `SynchronizedStockDeduction.deduct` 안의 조회 → 계산 → 덮어쓰기만 모니터로 감싼다. 커밋은 모니터 밖 | 트랜잭션 안에 건 락이 왜 안 되는가 |

구현은 `SynchronizedStockDeduction` (`STOCK_STRATEGY=synchronized`). 차감은 naive 와 같은 "조회 → 계산 → 벌크 UPDATE 덮어쓰기" 라
`@Version` 을 타지 않고, 정합성은 오로지 모니터가 책임진다. `StockDeductionStrategy.serialize(Supplier)` 기본 메서드를 재시도 계층이
호출하고, 이 전략만 `create` 범위에서 `synchronized` 로 재정의한다. 다른 전략은 통과.

## 실행 조건 (두 라운드 공통)

| 항목 | 값 |
|---|---|
| VU / 총 요청 | 100 / 10,000 (소비자 1인당 1건, `quantity` 1) |
| `SQL_LOG_LEVEL` / `SQL_BIND_LOG_LEVEL` | `warn` / `off` |
| `STOCK_RETRY_MAX_ATTEMPTS` / `STOCK_RETRY_BACKOFF` | 3 / `PT0.01S` (충돌 예외가 없어 항상 1회 통과) |
| 기동 방식 | `bootJar` → `IEUM_BE` 에서 `java -jar`, 두 서버 모두. 라운드마다 API 서버만 재기동해 카운터 0 |
| HikariCP `maximum-pool-size` | 20 |
| 코드 | `dev_be` `1481a30` — `SynchronizedStockDeduction`, `StockDeductionStrategy.serialize`, `OrderCreateRetrier` 의 `stock.serialize(...)` |
| 라운드 전 | MySQL 볼륨이 초기화되어 있어 서버 기동으로 스키마 생성 후 `seed-loadtest.sql` 재실행 (상품 id 1). 라운드 사이 `reset-loadtest.sql` + `SET stock:1 100` + `CONFIG RESETSTAT` |
| 행 락 통계 | k6 전후 `SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'`. MySQL 재기동 후 누적 0 이라 사후 값이 곧 증가분 |
| 실행 환경 | 이전 라운드와 동일 (Ryzen 5 5600, 16GB, Windows 11, Docker Desktop, k6 v2.2.0 호스트 실행) |

---

## S2 — 락 범위 `create` (정합성 성립)

### 결과 요약

| 항목 | 값 (synchronized/create) | 값 (redis 라운드 1) | 값 (conditional) | 출처 |
|---|---|---|---|---|
| 201 (예약 성공) | **100** | 100 | 100 | k6 `created 201` |
| 409 | 9,900 | 9,900 | 9,900 | k6 `sold out 409` |
| 503 / 500 | 0 / 0 | 0 / 0 | 0 / 0 | k6 |
| 초과 예약 | **0** | 0 | 0 | |
| DB `PENDING` 주문 수 / 수량 합 | 100 / 100 | 100 / 100 | 100 / 100 | 아래 SQL |
| DB `remaining_quantity` / `version` | 0 / 0 | 100 (투영) / 0 | 0 / 0 | 아래 SQL |
| 불변식 `initial = remaining + active` | 성립 | 성립 (Redis 기준) | 성립 | |
| `order.create.attempts{success / conflict / exhausted}` | 100 / 0 / 0 | 100 / 0 / 0 | 100 / 0 / 0 | Prometheus |
| 롤백된 주문 INSERT | 0 | 0 | 0 | `MAX(id) − COUNT(*)` |
| `hikaricp.connections.pending` 최대 / `active` 최대 | **0 / 1** | 79 / 20 | 80 / 20 | 스크랩 |
| `hikaricp.connections.acquire` 평균 / 최대 | **3µs / 13ms** | 114ms / 1.62s | 95ms / 1.00s | Prometheus |
| `Innodb_row_lock_waits` / `time` | **0 / 0** | 0 / 0 | 118 / 13,903ms | `SHOW GLOBAL STATUS` |
| 예약 요청 지연 med / p95 / p99 / max | **463ms / 951ms / 1.41s / 2.55s** | 129 / 277 / 808ms / 2.03s | 105 / 236 / 669ms / 1.67s | k6 `{ name:create-order }` |
| 예약 구간 시간 | **49.1s** | 15.8s | 13.2s | k6 진행 줄 |
| 예약 처리량 | **≈ 204 req/s** | ≈ 633 req/s | ≈ 758 req/s | 계산 |
| 재고 소진까지 | 1.70초 | 0.40초 | 0.76초 | DB `created_at` |

### 해석

**정합성은 맞는다.** 201 정확히 100, 롤백 0, 행 락 대기 0, `version` 0. 모니터가 트랜잭션 시작부터 커밋까지 감싸므로 다음 요청은 항상
커밋된 값을 읽는다. 행 락 대기가 0 인 이유도 같다. 동시에 한 트랜잭션만 열려 있어 InnoDB 에서 줄을 설 일이 없다.

**대신 모든 요청이 한 줄로 선다.** 재고가 0 이 된 뒤의 9,900 건도 모니터를 통과해야 409 를 받는다. 트랜잭션 하나(SELECT 3 + UPDATE + INSERT + commit,
소진 후에는 SELECT 3 + 롤백) 가 ≈ 5ms 이므로 10,000 건 ÷ 5ms ≈ 49초. **`pending` 0 / `active` 1** 이 이 구조를 그대로 보여 준다.
줄이 DB 커넥션 풀 앞에서 JVM 모니터 앞으로 옮겨 갔을 뿐이고, 풀 20 중 19 개는 놀았다. `acquire` 평균 3µs 는 "커넥션은 언제나 남아 있다" 는 뜻이다.

**p99 1.41s, 중앙값 463ms.** VU 100 이 모니터 하나에 줄을 서므로 대기 ≈ 99 × 5ms ≈ 0.5s 가 중앙값이 되고, 꼬리는 그 두세 배다.
conditional 의 p99 669ms 는 커넥션 풀 대기(20 개가 동시에 진행) 였는데, 여기는 진행 폭이 1 이라 같은 부하에서 지연이 두 배다.

**재고 소진 1.70초.** redis 0.40초, conditional 0.76초보다 길다. 성공 100 건도 직렬로 커밋되기 때문이다. conditional 의 X 락 직렬화(≈ 7.6ms 간격) 와
같은 원리이고, 여기서는 모니터가 직렬화 지점이다.

**JVM 하나에서만 유효하다.** 모니터는 프로세스 안의 것이다. Pod 2 개면 두 모니터가 각자 직렬화하고 둘 사이에는 아무 보호도 없어 naive 로 돌아간다.
이것이 V3(Kubernetes) 에서 `synchronized` 를 후보에서 지우는 근거이며, 이 라운드의 숫자는 "단일 인스턴스에서조차 처리량이 1/3" 이라는 추가 근거다.

**redis 와의 대비.** 같은 정합성인데 처리량 204 대 633 req/s (3.1배), p99 1.41s 대 808ms, 재고 소진 1.70 대 0.40초. Redis 는 직렬화 구간이
Lua 스크립트 20µs 이고 나머지는 병렬이다. `synchronized` 는 직렬화 구간이 트랜잭션 전체 ≈ 5ms 다. 둘의 차이는 "무엇을 직렬화하느냐" 의 차이다.

---

## S1 — 락 범위 `deduct` (트랜잭션 안, 초과 예약)

### 결과 요약

| 항목 | 값 (synchronized/deduct) | 값 (naive, 09-16 계측) | 출처 |
|---|---|---|---|
| 201 (예약 성공) | **2,854** | 1,996 | k6 `created 201` |
| 초과 예약 | **2,754** | 1,896 | 201 − 100 |
| 409 | 7,146 | 8,004 | k6 |
| 503 / 500 | 0 / 0 | 0 / 0 | k6 |
| DB `PENDING` 주문 수 | 2,854 | 1,996 | 아래 SQL |
| DB `remaining_quantity` / `version` | 0 / 0 | 0 / 0 | 아래 SQL |
| 불변식 `initial = remaining + active` | **깨짐** (100 ≠ 0 + 2,854) | 깨짐 | |
| 롤백된 주문 INSERT | 0 | 0 | `MAX(id) − COUNT(*)` |
| `hikaricp.connections.pending` 최대 / `active` 최대 | 81 / 20 | 80 / 20 | 스크랩 |
| `hikaricp.connections.acquire` 평균 / 최대 | 165ms / 672ms | 141ms / — | Prometheus |
| `Innodb_row_lock_waits` / `time` / 평균 / 최대 | **2,853 / 11,463ms / 4ms / 142ms** | (미측정) | `SHOW GLOBAL STATUS` |
| 예약 요청 지연 med / p95 / p99 / max | 94ms / 602ms / 696ms / 1.17s | 108 / — / 633ms / — | k6 |
| 예약 구간 시간 / 처리량 | 21.9s / ≈ 457 req/s | ≈ 19s / ≈ 529 req/s | k6 |
| 첫 주문 → 마지막 주문 | **15.1초** | 약 10초 | DB `created_at` |

### 해석

**락을 걸었는데 naive 보다 더 많이 뚫렸다.** 이유는 락과 트랜잭션의 범위가 다르기 때문이다. `deduct` 는 `create` 의 `@Transactional` 안에서
호출되고, 모니터는 `deduct` 가 끝나면 풀리지만 UPDATE 의 커밋은 `create` 가 끝날 때다. 다음 스레드는 모니터를 잡은 시점에 아직 커밋되지
않은 값을 읽는다. 게다가 `create` 가 `findByUid` 로 이미 올려 둔 엔티티가 영속성 컨텍스트에 있어 `findById` 는 SQL 을 내지도 않고
트랜잭션 시작 시점의 스냅샷을 돌려준다. 모니터는 "읽고 쓰는 순서" 만 정해 주고 "무엇을 읽느냐" 는 못 바꾼다.

**행 락 대기 2,853 회 = 성공 2,854 건 − 1.** 이 숫자가 범위 불일치의 직접 증거다. 모니터 안에서 UPDATE 를 보낸 스레드는 X 락을 잡은 채
모니터를 놓고 나가 커밋을 기다린다. 다음 스레드는 모니터를 잡고 (스냅샷을 읽고) UPDATE 를 보내지만 앞 트랜잭션의 X 락에 막혀 커밋될 때까지
기다린다 (평균 4ms). 커밋되면 자기 스냅샷 − 1 로 덮어쓴다. 성공한 UPDATE 마다 정확히 한 번씩 앞 커밋을 기다렸으므로 대기 횟수가 성공 − 1 이다.
즉 **모니터를 풀고 나서 커밋된다** 는 것을 InnoDB 가 세어 준 셈이다.

**왜 naive 보다 많이 뚫렸나.** 모니터가 UPDATE 를 직렬화해 재고가 DB 상 0 이 되기까지 15.1초가 걸렸다 (naive 약 10초). 그동안 100 VU 가 계속
0 이 아닌 스냅샷을 읽어 통과했다. 락이 경합 창을 좁힌 것이 아니라 늘렸다.

**결론.** `synchronized` 를 트랜잭션 안에 걸면 정합성에 아무 기여가 없다. 맞게 걸려면 S2 처럼 트랜잭션 밖에서 커밋까지 감싸야 하고, 그 순간 위의
처리량 1/3 을 지불한다. 그리고 어느 범위든 인스턴스 하나를 넘지 못한다.

---

## DB 확인 쿼리와 결과

S2 (`create`):

```text
order_state	cnt	qty
PENDING	100	100

initial_quantity	remaining_quantity	version
100	0	0

max_id	cnt	rolled_back_inserts
100	100	0

first_order	last_order	seconds
2026-09-23 00:54:33.021666	2026-09-23 00:54:34.726196	1.7045
```

S1 (`deduct`):

```text
order_state	cnt	qty
PENDING	2854	2854

initial_quantity	remaining_quantity	version
100	0	0

max_id	cnt	rolled_back_inserts
2854	2854	0

first_order	last_order	seconds
2026-09-23 00:58:26.426599	2026-09-23 00:58:41.550555	15.1240
```

`SHOW ENGINE INNODB STATUS\G` 에 `LATEST DETECTED DEADLOCK` 절 없음 (두 라운드 모두).

## 행 락 통계 (k6 전 → 후)

```text
                          S2 (create)      S1 (deduct)
Innodb_row_lock_waits     0 →    0         0 → 2853
Innodb_row_lock_time      0 →    0         0 → 11463  (ms)
Innodb_row_lock_time_avg  0 →    0         0 →    4
Innodb_row_lock_time_max  0 →    0         0 →  142
```

## 스크랩 타임라인

S2 — `active` 최대 | `pending` 최대 | success 누적. 예약 구간 49초 내내 `active` 1, `pending` 0.

```text
00:54:33   0 |  0 |   16   ← 예약 시작
00:54:34   1 |  0 |  100   ← 재고 0 (1.70초). 이후 47초 동안 409 만 직렬로
00:55:20   1 |  0 |  100
00:55:21   0 |  0 |  100   ← 예약 구간 끝 (49.1s)
```

S1 — `pending` 최대 | success 누적 (재고 100 을 넘어 계속 는다).

```text
00:58:26  81 |    20
00:58:28  79 |   358
00:58:30  80 |   731
00:58:32  79 |   992
00:58:34  80 |  1374
00:58:36  80 |  1780
00:58:38  80 |  2215
00:58:40  79 |  2648
00:58:41  79 |  2798   ← 마지막 201 (15.1초)
00:58:42  79 |  2854
00:58:47  75 |  2854   ← 예약 구간 끝 (21.9s)
```

## k6 출력 (진행 줄 생략)

S2 (`STOCK_SYNC_SCOPE=create`):

```text
  █ THRESHOLDS
    http_req_duration{name:create-order}
    ✓ 'p(99)<60000' p(99)=1.41s
    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=150.19ms

  █ TOTAL RESULTS
    checks_total.......: 30000  151.256454/s
    checks_succeeded...: 33.33% 10000 out of 30000
    checks_failed......: 66.66% 20000 out of 30000

    ✗ created 201
      ↳  1% — ✓ 100 / ✗ 9900
    ✗ sold out 409
      ↳  99% — ✓ 9900 / ✗ 100
    ✗ contention 503
      ↳  0% — ✓ 0 / ✗ 10000

    HTTP
    http_req_duration..............: avg=283.66ms med=132.3ms  p(95)=840.27ms p(99)=1.14s    max=2.55s
      { expected_response:true }...: avg=96.65ms  med=75.36ms  p(95)=144.92ms p(99)=174.87ms max=2.52s
      { name:create-order }........: avg=482ms    med=462.52ms p(95)=951.28ms p(99)=1.41s    max=2.55s
      { name:login }...............: avg=85.33ms  med=75.29ms  p(95)=143.92ms p(99)=150.19ms max=216.87ms
    http_req_failed................: 49.50% 9900 out of 20000
    http_reqs......................: 20000  100.837636/s

    EXECUTION
    iteration_duration.............: avg=482.61ms med=462.66ms p(95)=951.92ms p(99)=1.41s    max=2.57s
    iterations.....................: 10000  50.418818/s
    vus............................: 90     min=0             max=100
    vus_max........................: 100    min=100           max=100

order ✓ [ 100% ] 100 VUs  00m49.1s/10m0s  10000/10000 shared iters
```

S1 (`STOCK_SYNC_SCOPE=deduct`):

```text
  █ THRESHOLDS
    http_req_duration{name:create-order}
    ✓ 'p(99)<60000' p(99)=696.01ms
    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=147.07ms

  █ TOTAL RESULTS
    checks_total.......: 30000  178.320785/s
    checks_succeeded...: 33.33% 10000 out of 30000
    checks_failed......: 66.66% 20000 out of 30000

    ✗ created 201
      ↳  28% — ✓ 2854 / ✗ 7146
    ✗ sold out 409
      ↳  71% — ✓ 7146 / ✗ 2854
    ✗ contention 503
      ↳  0% — ✓ 0 / ✗ 10000

    HTTP
    http_req_duration..............: avg=146.53ms med=77.05ms p(95)=541.69ms p(99)=655.15ms max=1.17s
      { expected_response:true }...: avg=177.24ms med=76.37ms p(95)=583.45ms p(99)=682.51ms max=1.17s
      { name:create-order }........: avg=209.52ms med=93.57ms p(95)=602.09ms p(99)=696.01ms max=1.17s
      { name:login }...............: avg=83.55ms  med=74.6ms  p(95)=140.46ms p(99)=147.07ms max=181.15ms
    http_req_failed................: 35.73% 7146 out of 20000
    http_reqs......................: 20000  118.880523/s

    EXECUTION
    iteration_duration.............: avg=210.07ms med=94.12ms p(95)=602.36ms p(99)=697.67ms max=1.2s
    iterations.....................: 10000  59.440262/s
    vus............................: 100    min=0             max=100
    vus_max........................: 100    min=100           max=100

order ✓ [ 100% ] 100 VUs  00m21.9s/10m0s  10000/10000 shared iters
```

## Prometheus 최종 스냅샷

S2:

```text
order_create_attempts_total{outcome="success"} 100.0
order_create_attempts_total{outcome="conflict"} 0.0
order_create_attempts_total{outcome="exhausted"} 0.0
order_create_attempts_total{outcome="deadlock"} 0.0
order_create_attempts_used_count 100
order_create_attempts_used_max 1.0
hikaricp_connections_acquire_seconds_count{pool="IeumHikariPool"} 10002
hikaricp_connections_acquire_seconds_sum{pool="IeumHikariPool"} 0.0306818
hikaricp_connections_acquire_seconds_max{pool="IeumHikariPool"} 0.0130184
```

S1:

```text
order_create_attempts_total{outcome="success"} 2854.0
order_create_attempts_total{outcome="conflict"} 0.0
order_create_attempts_total{outcome="exhausted"} 0.0
order_create_attempts_total{outcome="deadlock"} 0.0
hikaricp_connections_acquire_seconds_count{pool="IeumHikariPool"} 10002
hikaricp_connections_acquire_seconds_sum{pool="IeumHikariPool"} 1649.7491731
hikaricp_connections_acquire_seconds_max{pool="IeumHikariPool"} 0.672151
```

## 다음 라운드 전

`reset-loadtest.sql` + `SET stock:1 100` 으로 초기화하고 `.env` 의 `STOCK_STRATEGY` 를 `redis` 로 되돌렸다.
두 라운드가 ADR-0003 에 넘기는 것:

- `synchronized` 는 범위를 맞추면(트랜잭션 밖) 정합성이 맞지만 처리량이 redis 의 1/3, conditional 의 1/4 이고 커넥션 풀 19 개가 논다
- 범위를 못 맞추면(트랜잭션 안) naive 보다 나쁘다. 행 락 대기 = 성공 − 1 이 그 증거
- 어느 쪽이든 인스턴스 하나를 넘지 못한다. V3 에서 후보에서 제외하는 근거
