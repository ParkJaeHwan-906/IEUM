# 부하 테스트 기록 — 3단계 Redis Lua (redis) 두 라운드

todo 2.1 동시성 제어의 3번. 재고 원장을 `stores_items.remaining_quantity` 에서 Redis `stock:{itemId}` 로 옮기고 Lua 스크립트 한 번으로
"확인 후 차감" 을 끝낸다. 예약 생성 트랜잭션은 `stores_items` 를 UPDATE 하지 않는다. 비교 기준은
[(선택) 조건부 UPDATE](./2026-09-17-stage2-conditional-update.md) (09-17). 구현 배경과 절차는 [3단계 가이드](../guides/stage3-redis-lua-guide.md).
같은 날 [synchronized 두 라운드](./2026-09-23-synchronized.md) 도 측정했다.

같은 설정으로 두 번 돌렸다 (사이에 synchronized 두 라운드). 두 라운드의 정합성 지표는 완전히 같고 지연·처리량은 편차가 커서
둘 다 적는다. 편차 자체가 이 라운드의 발견 중 하나다.

## 실행 조건

| 항목 | 값 |
|---|---|
| `STOCK_STRATEGY` | `redis` — `RedisStockDeduction`. Lua `stock-deduct.lua` (GET → 비교 → DECRBY) 를 `EVALSHA` 로 실행. 키 없음(-2) 이면 DB 값으로 `SET NX` 후 재실행, 부족(-1) 이면 409. 롤백 시 보상 `INCRBY` (`afterCompletion`), 복구는 `afterCommit` |
| DB 열 | `stores_items.remaining_quantity` 는 읽지도 쓰지도 않는다 (투영). 불변식은 `initial = GET stock:{id} + 활성 주문 수량` 으로 확인 |
| `STOCK_RETRY_MAX_ATTEMPTS` / `STOCK_RETRY_BACKOFF` | 3 / `PT0.01S` (충돌 예외가 없어 항상 1회 통과) |
| VU / 총 요청 | 100 / 10,000 (소비자 1인당 1건, `quantity` 1) |
| `SQL_LOG_LEVEL` / `SQL_BIND_LOG_LEVEL` | `warn` / `off` |
| 기동 방식 | `bootJar` → `IEUM_BE` 에서 `java -jar`, 두 서버 모두 (이전 라운드는 인증 서버가 IntelliJ 실행이었음). 라운드마다 API 서버 재기동으로 카운터 0 |
| HikariCP `maximum-pool-size` | 20 |
| Lettuce | 기본 설정. `commons-pool2` 가 클래스패스에 없어 `lettuce.pool` 설정은 무시되고 **네이티브 커넥션 1개를 공유**한다 (해석 참조) |
| 코드 | `dev_be` `02202cb` (라운드 1) / `1481a30` (라운드 2, synchronized 전략 추가만 다름) — `RedisStockDeduction`, `stock-deduct.lua`, `StockDeductionStrategy.initialize` |
| 라운드 전 | MySQL 볼륨이 초기화되어 있어 서버 기동으로 스키마 생성 후 `seed-loadtest.sql` 재실행 (상품 id 1). 예약 1건으로 경로 확인: DB `remaining_quantity` 100 그대로, `GET stock:1` 99, `version` 0, `warmup` 0. 이후 `reset-loadtest.sql` + `SET stock:1 100` + `CONFIG RESETSTAT` |
| 행 락 통계 | k6 전후 `SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'`. 라운드 1 은 MySQL 재기동 후 누적 0, 라운드 2 는 그 사이 synchronized S1 이 남긴 2,853 이 기준선 |
| Redis 통계 | k6 직전 `CONFIG RESETSTAT`, 직후 `INFO commandstats`. Lua 안의 `redis.call` 도 명령별로 집계된다 (`get` 10,000 = 스크립트의 GET, `decrby` 100). `set` 10,000 은 인증 서버의 Refresh Token 발급 (같은 Redis) |
| 스크랩 | 0.5초 간격 → `scripts/k6/out/stage3-redis*/` (저장소 제외) |
| 실행 환경 | 이전 라운드와 동일 (Ryzen 5 5600, 16GB, Windows 11, Docker Desktop, k6 v2.2.0 호스트 실행) |

## 결과 요약

| 항목 | 라운드 1 | 라운드 2 | conditional (09-17) | 출처 |
|---|---|---|---|---|
| 201 (예약 성공) | **100** | **100** | 100 | k6 `created 201` |
| 409 (재고 부족) | 9,900 | 9,900 | 9,900 | k6 `sold out 409` |
| 503 (재시도 소진) / 500 | 0 / 0 | 0 / 0 | 0 / 0 | k6 |
| 초과 예약 | **0** | **0** | 0 | |
| DB `PENDING` 주문 수 / 수량 합 | 100 / 100 | 100 / 100 | 100 / 100 | 아래 SQL |
| DB `remaining_quantity` / `version` | **100 / 0** (건드리지 않음) | **100 / 0** | 0 / 0 | 아래 SQL |
| Redis `stock:1` 최종값 | **0** | **0** | — | `GET` |
| 불변식 `initial = Redis stock + active` | **성립** (100 = 0 + 100) | 성립 | 성립 (DB 기준) | |
| `stock.redis.deduct{success / insufficient / warmup / compensated}` | 100 / 9,900 / **0 / 0** | 100 / 9,900 / 0 / 0 | — | Prometheus |
| `order.create.attempts{success / conflict / exhausted / deadlock}` | 100 / 0 / 0 / 0 | 100 / 0 / 0 / 0 | 100 / 0 / 0 / 0 | Prometheus |
| 커넥션 획득 횟수 | 10,000 | 10,000 | 10,000 | `acquire_seconds_count` (+2 는 기동 시) |
| 롤백된 주문 INSERT | 0 | 0 | 0 | `MAX(id) − COUNT(*)` |
| `Innodb_row_lock_waits` 증가분 | **0** | **0** | 118 | `SHOW GLOBAL STATUS` |
| `hikaricp.connections.pending` 최대 / `active` 최대 | 79 / 20 | 80 / 20 | 80 / 20 | 스크랩 |
| `hikaricp.connections.acquire` 평균 / 최대 | 114ms / 1.62s | 157ms / 1.14s | 95ms / 1.00s | Prometheus |
| Redis `evalsha` 호출 / 평균 (서버 측) | 10,000 / **20.5µs** | 10,000 / 22.6µs | — | `INFO commandstats` |
| `stock.redis.script` 평균 / 최대 (애플리케이션 측) | **4.3ms** / 227ms | 5.8ms / 180ms | — | Prometheus |
| 예약 요청 지연 med / p95 / p99 / max | 129ms / 277ms / **808ms** / 2.03s | 181ms / 407ms / **589ms** / 1.57s | 105 / 236 / 669ms / 1.67s | k6 `{ name:create-order }` |
| 로그인 지연 med / p99 | 76ms / 153ms | 73ms / 146ms | 93ms / 185ms | k6 `{ name:login }` |
| 예약 구간 시간 | 15.8s | 22.0s | 13.2s | k6 진행 줄 |
| 예약 처리량 | ≈ 633 req/s | ≈ 455 req/s | ≈ 758 req/s | 계산 |
| 재고 소진까지 | **0.40초** | **0.69초** | 0.76초 | DB `created_at` |
| InnoDB `LATEST DETECTED DEADLOCK` | 없음 | 없음 | 없음 | |

여섯 측정을 나란히:

| | naive (09-16) | optimistic 라운드 2 | conditional | **redis 1 / 2** | synchronized create | synchronized deduct |
|---|---|---|---|---|---|---|
| 201 | 1,996 | 100 | 100 | **100 / 100** | 100 | 2,854 |
| 초과 예약 | 1,896 | 0 | 0 | **0** | 0 | 2,754 |
| 503 | 0 | 574 | 0 | **0** | 0 | 0 |
| 행 락 대기 | — | — | 118 | **0** | 0 | 2,853 |
| 재고 소진 | 약 10초 | 약 5초 | 0.76초 | **0.40 / 0.69초** | 1.70초 | (소진 안 됨) |
| p99 | 633ms | 790ms | 669ms | **808 / 589ms** | 1.41s | 696ms |
| 처리량 | 529 | 719 | 758 | **633 / 455** req/s | 204 | 457 |
| `pending` 최대 / `active` 최대 | 80 / 20 | 79 / 20 | 80 / 20 | **79 / 20** | 0 / 1 | 81 / 20 |
| 정합성을 지키는 것 | 없음 | `@Version` + 재시도 | 조건부 UPDATE | **Lua 원자성** | JVM 모니터 | 없음 (범위 불일치) |

## 해석

**정합성은 맞고, 재고 행 직렬화는 사라졌다.** 201 정확히 100, 보상 0, 워밍업 0, 롤백 0. `Innodb_row_lock_waits` 증가분 0 — conditional 의 118 이
0 이 됐다. 예약 트랜잭션에 `stores_items` UPDATE 가 없으니 X 락을 잡을 일이 없고, INSERT 의 FK 검사가 잡는 S 락은 서로 충돌하지 않는다.
DB `remaining_quantity` 는 100 그대로이고 `version` 0. 원장이 DB 밖으로 나갔다는 것이 이 두 숫자다.

**재고 소진 0.40초 (라운드 2 는 0.69초).** conditional 0.76초, synchronized/create 1.70초. 성공 100 건이 더 이상 한 줄로 커밋되지 않는다.
Redis 쪽 `evalsha` 는 호출당 20µs 라, 직렬화 구간이 7.6ms(행 X 락 보유) 에서 20µs 로 줄었다. 소진 시간의 나머지는 "100 VU 가 첫 요청 무리를
보내는 시간" 이며 3단계 가이드의 예상대로다.

**그런데 p99 와 처리량은 conditional 보다 좋지 않다.** 라운드 1 은 p99 808ms / 633 req/s, 라운드 2 는 589ms / 455 req/s. 정합성 지표가 완전히 같은
두 라운드가 지연에서 이만큼 갈리는 것부터가 첫 발견이다. 이 부하에서 지연은 전략이 아니라 VU 100 / 풀 20 커넥션 대기가 만들며, 그 줄의 길이는
라운드마다 달라진다 (`pending` 은 매번 80 근처지만 `acquire` 평균은 95 → 114 → 157ms). 로그인 p99 도 라운드마다 146~185ms 로 흔들린다.
**전략 간 p99 비교는 ±100ms 안에서는 의미가 없다** 는 것을 여섯 측정이 함께 말해 준다.

그 위에서 Redis 가 conditional 보다 불리한 구조적 이유가 하나 있다. **Lua 호출이 DB 트랜잭션 안, 즉 커넥션을 쥔 채로 실행된다.** 애플리케이션이 잰
`stock.redis.script` 평균은 4.3~5.8ms 인데 서버는 20µs 다. 그 차이는 Lettuce 다. `commons-pool2` 가 클래스패스에 없어 `lettuce.pool` 설정은
무시되고 네이티브 커넥션 하나를 100 스레드가 공유한다. 명령은 그 커넥션의 이벤트 루프 하나에서 직렬로 나가고, 응답을 기다리는 스레드 100 개의
스케줄링이 밀리초 단위의 대기를 만든다 (최대 180~227ms). 소진 후 9,900 건은 conditional 에서는 영속성 컨텍스트 스냅샷만 보고 409 로 끝났는데,
redis 에서는 Redis 왕복 ≈ 5ms 를 커넥션을 쥔 채 기다린다. 요청당 커넥션 보유가 5ms 늘면 풀 20 앞의 줄이 그만큼 길어진다. 라운드 2 의 `acquire`
평균 157ms 가 conditional 95ms 보다 큰 이유의 일부다.

**Redis 자체는 병목이 아니다.** `evalsha` 10,000 회 총 205~226ms. Lua 안의 `get` 10,000 회는 호출당 3µs. 부하 구간 20초 중 Redis 가 일한 시간은
0.2초다. 병목은 Redis 앞의 클라이언트 쪽(단일 공유 커넥션 + 스레드 대기) 과 DB 커넥션 풀이다.

**보상 복구 0.** 부하 중 Redis↔DB 창(차감 후 커밋 실패) 이 한 번도 열리지 않았다. 창이 없다는 뜻은 아니다. 3단계 가이드 1.4 의 두 창(차감 후 커밋 전
크래시, 취소 커밋 후 INCRBY 전 크래시) 은 그대로 있고 Reconciliation 이 잡는다.

### 3단계로 해결되는 것과 남는 것

- 해결: 초과 예약 0, 503 0, 재시도 0, 데드락 0, **재고 행 직렬화 0**. 한 상품의 성공 처리 상한(≈ 130 건/s) 이 풀렸다. 규칙(`remaining >= qty`) 은 SQL 에서 Lua 로 옮겨 갔다
- 남는 것
  - **Lua 호출이 DB 커넥션 보유 중에 있다.** `create` 가 `findByUid` 로 상품을 읽어야 `itemId` 를 알기 때문이다. 사전 검사(판매 조건·중복 활성 예약) 와 재고 판정을
    DB 트랜잭션 **앞** 으로 빼면 소진 후 9,900 건은 커넥션 없이 409 로 끝난다. README V1 의 "하나의 원자적 연산에 사용자 활성 예약 확인까지" 가 그 방향이다
  - **Lettuce 단일 공유 커넥션.** `commons-pool2` 추가로 풀링을 켜거나, 위 사전 검사 분리와 함께 재는 것이 다음 실험. 서버 20µs 대 클라이언트 5ms 의 간격이 목표
  - **DB `remaining_quantity` 가 투영이 됐다.** `GET /api/items/{itemUid}` 의 남은 수량이 stale 이다. 조회 경로가 Redis 를 보거나 Reconciliation 이 DB 열을 갱신해야 한다
  - **Reconciliation 은 필수다.** 워밍업(`SET NX`) 이 DB 투영에서 값을 가져오므로 투영이 stale 이면 Redis 재기동 후 원장도 stale 이다. 복구 상한(`initial`) 검사도 Redis 에 없다
  - 중복 활성 예약 검사의 틈 (변함없음, DB 조회 기반)
  - **라운드 간 편차.** 같은 코드·같은 시드에서 p99 808 → 589ms, 처리량 633 → 455 req/s. 이 환경(Windows Docker Desktop, 호스트 k6) 에서 지연 수치는 두 자릿수 % 로 흔들린다.
    앞으로의 비교는 정합성·행 락·소진 시간·카운터 같은 결정적 지표를 우선하고, 지연은 여러 라운드의 범위로 적는다

## DB 확인 쿼리와 결과

라운드 1:

```text
order_state	cnt	qty
PENDING	100	100

initial_quantity	remaining_quantity	version
100	100	0

max_id	cnt	rolled_back_inserts
100	100	0

first_order	last_order	seconds
2026-09-23 00:50:39.579502	2026-09-23 00:50:39.976009	0.3965
```

라운드 2:

```text
order_state	cnt	qty
PENDING	100	100

initial_quantity	remaining_quantity	version
100	100	0

max_id	cnt	rolled_back_inserts
100	100	0

first_order	last_order	seconds
2026-09-23 01:01:50.075017	2026-09-23 01:01:50.768331	0.6933
```

`GET stock:1` → `0` (두 라운드). 불변식 100 = 0 + 100. `SHOW ENGINE INNODB STATUS\G` 에 `LATEST DETECTED DEADLOCK` 절 없음.

## 행 락 통계 (k6 전 → 후)

```text
                          라운드 1        라운드 2 (기준선은 synchronized S1 이 남긴 값)
Innodb_row_lock_waits     0 → 0          2853 → 2853
Innodb_row_lock_time      0 → 0          11463 → 11463  (ms)
```

## Redis 명령 통계 (`CONFIG RESETSTAT` → k6 → `INFO commandstats`)

```text
라운드 1
cmdstat_evalsha:calls=10000,usec=204880,usec_per_call=20.49
cmdstat_get:calls=10001,usec=31771,usec_per_call=3.18        ← 스크립트 안의 GET 10,000 + 확인용 1
cmdstat_decrby:calls=100,usec=142,usec_per_call=1.42         ← 스크립트 안의 DECRBY, 성공 100
cmdstat_set:calls=10000,usec=66017,usec_per_call=6.60        ← 인증 서버 Refresh Token 발급 (로그인 10,000)

라운드 2
cmdstat_evalsha:calls=10000,usec=226272,usec_per_call=22.63
cmdstat_get:calls=10001,usec=32845,usec_per_call=3.28
cmdstat_decrby:calls=100,usec=167,usec_per_call=1.67
cmdstat_set:calls=10000,usec=61629,usec_per_call=6.16
```

## 스크랩 타임라인 (초당 `pending` 최대 | success / insufficient 누적)

라운드 1:

```text
00:50:39   0 |  20 /     0   ← 예약 시작
00:50:40  74 | 100 /   299   ← 재고 0 (0.40초)
00:50:44  76 | 100 /  2949
00:50:48  78 | 100 /  5410
00:50:52  79 | 100 /  8655
00:50:53  76 | 100 /  9900   ← 예약 구간 끝 (15.8s)
```

라운드 2:

```text
01:01:50  66 |  33 /     0
01:01:51  75 | 100 /   391   ← 재고 0 (0.69초)
01:01:55  79 | 100 /  3029
01:02:00  78 | 100 /  4875
01:02:05  78 | 100 /  7082
01:02:10  79 | 100 /  9900   ← 예약 구간 끝 (22.0s)
```

## k6 출력 (진행 줄 생략)

라운드 1:

```text
  █ THRESHOLDS
    http_req_duration{name:create-order}
    ✓ 'p(99)<60000' p(99)=807.92ms
    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=153.24ms

  █ TOTAL RESULTS
    checks_total.......: 30000  179.182278/s
    checks_succeeded...: 33.33% 10000 out of 30000
    checks_failed......: 66.66% 20000 out of 30000

    ✗ created 201
      ↳  1% — ✓ 100 / ✗ 9900
    ✗ sold out 409
      ↳  99% — ✓ 9900 / ✗ 100
    ✗ contention 503
      ↳  0% — ✓ 0 / ✗ 10000

    HTTP
    http_req_duration..............: avg=118.37ms med=94.15ms  p(95)=224.49ms p(99)=381.28ms max=2.03s
      { expected_response:true }...: avg=98.28ms  med=76.12ms  p(95)=145.98ms p(99)=201.94ms max=1.77s
      { name:create-order }........: avg=150.12ms med=128.68ms p(95)=277.16ms p(99)=807.92ms max=2.03s
      { name:login }...............: avg=86.62ms  med=76.08ms  p(95)=144.78ms p(99)=153.24ms max=404.92ms
    http_req_failed................: 49.50% 9900 out of 20000
    http_reqs......................: 20000  119.454852/s

    EXECUTION
    iteration_duration.............: avg=151.1ms  med=129.57ms p(95)=278.55ms p(99)=808.1ms  max=2.04s
    iterations.....................: 10000  59.727426/s
    vus............................: 100    min=0             max=100
    vus_max........................: 100    min=100           max=100

order ✓ [ 100% ] 100 VUs  00m15.8s/10m0s  10000/10000 shared iters
```

라운드 2:

```text
  █ THRESHOLDS
    http_req_duration{name:create-order}
    ✓ 'p(99)<60000' p(99)=588.51ms
    http_req_duration{name:login}
    ✓ 'p(99)<60000' p(99)=145.52ms

  █ TOTAL RESULTS
    checks_total.......: 30000  183.330276/s
    checks_succeeded...: 33.33% 10000 out of 30000
    checks_failed......: 66.66% 20000 out of 30000

    ✗ created 201
      ↳  1% — ✓ 100 / ✗ 9900
    ✗ sold out 409
      ↳  99% — ✓ 9900 / ✗ 100
    ✗ contention 503
      ↳  0% — ✓ 0 / ✗ 10000

    HTTP
    http_req_duration..............: avg=142.65ms med=116.25ms p(95)=332.01ms p(99)=519.2ms  max=1.57s
      { expected_response:true }...: avg=85.5ms   med=72.59ms  p(95)=137.75ms p(99)=163.27ms max=1.57s
      { name:create-order }........: avg=204.49ms med=181.26ms p(95)=406.99ms p(99)=588.51ms max=1.57s
      { name:login }...............: avg=80.81ms  med=72.51ms  p(95)=136.13ms p(99)=145.52ms max=183.16ms
    http_req_failed................: 49.50% 9900 out of 20000
    http_reqs......................: 20000  122.220184/s

    EXECUTION
    iteration_duration.............: avg=205.54ms med=182.09ms p(95)=408.31ms p(99)=591.04ms max=1.58s
    iterations.....................: 10000  61.110092/s
    vus............................: 100    min=0             max=100
    vus_max........................: 100    min=100           max=100

order ✓ [ 100% ] 100 VUs  00m22.0s/10m0s  10000/10000 shared iters
```

## Prometheus 최종 스냅샷

라운드 1:

```text
stock_redis_deduct_total{outcome="success"} 100.0
stock_redis_deduct_total{outcome="insufficient"} 9900.0
stock_redis_deduct_total{outcome="warmup"} 0.0
stock_redis_deduct_total{outcome="compensated"} 0.0
stock_redis_script_seconds_count 10000
stock_redis_script_seconds_sum 42.9403246
stock_redis_script_seconds_max 0.2265054
order_create_attempts_total{outcome="success"} 100.0
order_create_attempts_total{outcome="conflict"} 0.0
order_create_attempts_total{outcome="exhausted"} 0.0
order_create_attempts_total{outcome="deadlock"} 0.0
order_create_attempts_used_bucket{le="1.0"} 100
order_create_attempts_used_max 1.0
hikaricp_connections_acquire_seconds_count{pool="IeumHikariPool"} 10002
hikaricp_connections_acquire_seconds_sum{pool="IeumHikariPool"} 1140.9373637
hikaricp_connections_acquire_seconds_max{pool="IeumHikariPool"} 1.6172955
```

라운드 2:

```text
stock_redis_deduct_total{outcome="success"} 100.0
stock_redis_deduct_total{outcome="insufficient"} 9900.0
stock_redis_deduct_total{outcome="warmup"} 0.0
stock_redis_deduct_total{outcome="compensated"} 0.0
stock_redis_script_seconds_count 10000
stock_redis_script_seconds_sum 58.0425704
stock_redis_script_seconds_max 0.179569
order_create_attempts_total{outcome="success"} 100.0
order_create_attempts_total{outcome="conflict"} 0.0
order_create_attempts_total{outcome="exhausted"} 0.0
hikaricp_connections_acquire_seconds_count{pool="IeumHikariPool"} 10002
hikaricp_connections_acquire_seconds_sum{pool="IeumHikariPool"} 1574.0386286
hikaricp_connections_acquire_seconds_max{pool="IeumHikariPool"} 1.1398259
```

## 다음 라운드 전

`reset-loadtest.sql` + `SET stock:1 100` 으로 초기화하고 `.env` 의 `STOCK_STRATEGY` 를 `redis` 로 두었다. 3단계까지의 비교 측정이 끝났고
ADR-0003 의 결정을 확정한다. 다음 실험 후보 (측정 순서대로):

1. 사전 검사와 Lua 판정을 DB 트랜잭션 앞으로 → 소진 후 요청이 커넥션 없이 409. `acquire` 평균과 처리량이 어떻게 바뀌는지
2. `commons-pool2` 로 Lettuce 풀링 → `stock.redis.script` 평균이 5ms 에서 얼마나 내려가는지
3. Reconciliation Job (필수) + DB 투영 갱신 + 조회 API
