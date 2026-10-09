# 예약 생성 경로 가이드 — 비관적 락 · 낙관적 락 · Redis Lua 통합 판정

2026-10-09 에 구현한 예약 경로를 나중에 읽고 공부하기 위한 안내서. 결정의 배경은 [ADR-0004](../adr/0004-reservation-path.md),
수치는 [performance/2026-10-09-duplicate-burst.md](../performance/2026-10-09-duplicate-burst.md) 에 있다. 여기서는 **코드를 어떤 순서로 읽으면 되는지** 와
**구현하면서 실제로 걸렸던 함정** 을 남긴다.

## 1. 읽는 순서

| 순서 | 파일 (`ieum-api` / `com.hwannee.ieum.orders`) | 볼 것 |
|---|---|---|
| 1 | `web/OrderController` | 컨트롤러는 `OrderCreator` 인터페이스만 안다 |
| 2 | `service/OrderCreator` | 구현체 둘. `STOCK_STRATEGY=redis` 면 `RedisOrderCreator`, 그 외는 `OrderCreateRetrier` |
| 3 | `service/OrderCreateRetrier` | DB 전략의 바깥 루프. 낙관적 락 충돌·데드락 재시도, 멱등키 unique 위반 시 재생 |
| 4 | `service/OrderService#create` | DB 전략 공통 흐름: (잠금) 상품 → 계정 → 멱등키 → 판매 조건 → 중복 → 차감 → INSERT |
| 5 | `stock/PessimisticLockStockDeduction` | `locksItemRow()` 가 true. 차감 시 이미 잠긴 행을 다시 `FOR UPDATE` 로 읽음 |
| 6 | `stock/OptimisticLockStockDeduction` | `@Version` + `flush()` 선행 (ADR-0003) |
| 7 | `service/RedisOrderCreator` | redis 전략의 전체 흐름. `switch` 가 Lua 결과를 HTTP 응답으로 바꾼다 |
| 8 | `service/ItemSaleCache` | 판매 조건을 DB 커넥션 없이 판정하기 위한 5초 캐시 |
| 9 | `stock/RedisStockDeduction` | reserve · confirm · compensate · restore · settle · warmUp |
| 10 | `resources/redis/stock-*.lua` | reserve(판정+차감), compensate(되돌리기), release(1회 복구), warmup(DB 상태로 채우기) |
| 11 | `reconcile/StockReconciliationJob` | 두 번 연속 같은 어긋남만 정정, DB 투영 갱신, 만료 재등록 |
| 11-1 | `expiry/PendingTimeoutJob` | 승인 없이 5분이 지난 PENDING 을 DB 에서 찾아 취소·재고 복구 (ADR-0004 8절) |
| 12 | `ieum-domain` `UsersOrders` · `StoresItemsRepository` | `idempotency_key` + unique, `findByUidForUpdate` |

테스트는 `src/test/.../orders/integration/OrderConcurrencyScenario` 하나를 읽으면 세 전략의 공통 요구가 보인다. 하위 클래스는 "중복 틈을 닫는가" 와 "원장을 어디서 읽는가" 만 다르다.

## 2. 세 전략의 요청 흐름

```text
pessimistic   [tx] SELECT … FOR UPDATE(상품) → 계정 → 멱등키 → 판매 조건 → 중복 → remaining 검사·감소 → INSERT → COMMIT
              거절도 잠금을 받은 뒤에야 나간다. 한 상품의 모든 요청이 한 줄

optimistic    [tx] 상품 → 계정 → 멱등키 → 판매 조건 → 중복 → remaining 감소 + flush(@Version 검사) → INSERT → COMMIT
              충돌하면 바깥(OrderCreateRetrier)에서 새 트랜잭션으로 최대 3회. 중복 검사는 잠금 없이 읽지만,
              같은 사용자의 앞선 예약이 같은 행의 version 을 올렸으므로 이 트랜잭션은 반드시 충돌 → 재시도에서 중복으로 걸린다 (ADR-0004 2절)

redis         캐시(판매 조건) → Lua(멱등키·중복·재고 → 차감) → 거절이면 여기서 끝 (DB 0회)
              통과한 요청만 [tx] 계정 → 상품(PK) → INSERT → COMMIT → SET idem orderId
```

## 3. 실제로 걸렸던 함정

### 3.1 REPEATABLE READ 스냅샷과 비관적 락의 순서

처음 떠오르는 구현은 "상품 조회 → 검사들 → 차감 직전에 `FOR UPDATE`" 다. 이러면 중복 검사가 틀린다.
InnoDB 는 **첫 일반 SELECT** 에서 스냅샷을 만들고, 그 뒤 일반 SELECT 는 같은 스냅샷을 본다. 잠금을 기다리는 동안 앞 사람이 커밋한 주문은 이 스냅샷에 없다.
`FOR UPDATE` 같은 잠금 읽기는 최신 커밋 값을 읽지만 스냅샷을 새로 만들지는 않는다.
그래서 **잠금을 트랜잭션 첫 문장으로** 두었다. 그러면 첫 일반 SELECT(계정 조회) 가 잠금 획득 뒤에 나가 스냅샷이 잠금 이후 시점이 된다.

### 3.2 `jakarta.persistence.lock.timeout` 은 MySQL 에서 아무 일도 하지 않는다

`@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))` 를 붙였더니 SQL 로그에는 `for update of si1_0` 만 나왔고 `innodb_lock_wait_timeout` 은 50초 그대로였다.
Hibernate MySQL 방언은 0(NOWAIT) 과 -2(SKIP LOCKED) 만 SQL 로 옮긴다. 효과 없는 어노테이션은 지웠다. 바꾸려면 JDBC URL 의 `sessionVariables=innodb_lock_wait_timeout=3` 같은 세션 변수가 필요하다.

### 3.3 Spring 빈 생성자가 둘이면

`ItemSaleCache` 에 테스트용 package-private 생성자를 더 두었더니 컨텍스트가 `NoSuchMethodException` 으로 떴다. 생성자가 여럿이고 `@Autowired` 가 없으면 Spring 은 기본 생성자를 찾는다. 단위 테스트는 통과하고 `@SpringBootTest` 에서만 터진다.

### 3.4 Reconciliation 의 "같은 차이 두 번" 은 위험하다

트래픽이 계속되면 "Lua 는 지났고 커밋은 아직" 인 정상 예약이 늘 몇 건 있다. 그 수가 우연히 두 주기 연속 같으면 차이 값도 같다. 이것을 유실로 보고 `INCRBY` 하면 재고가 더 풀려 **초과 예약** 이 된다.
그래서 기대값·실제값·사용자 집합 전체가 같을 때만(= 그 상품에 60초 동안 아무 일도 없었을 때만) 정정한다.

### 3.5 커밋 뒤 단계의 실패를 예외로 올리면 안 된다

`RedisOrderCreator` 에서 `place()` 가 성공한 뒤의 `confirm()`(SET idem orderId) 이 실패했을 때 예외를 던지면, 바깥의 `catch` 가 보상(재고 되돌리기) 을 해 버린다. 주문은 이미 커밋되어 있는데 재고만 늘어난다.
보상은 `place()` 의 실패에만 걸고, 커밋 이후 단계는 로그만 남긴다. `restore` 의 `afterCommit` 도 같은 이유로 예외를 삼키고 카운터만 올린다.

### 3.6 부하 테스트: Windows 의 listen backlog

VU 1,000 × 동시 4요청 = 4,000 연결이 한 순간에 열리면 약 40% 가 `connection refused` 였다. `server.tomcat.accept-count=8192` 로도 그대로였다. Windows 데스크톱은 backlog 를 200 으로 묶는다.
서버 문제가 아니라 측정 환경 문제라 k6 쪽에서 VU 별 첫 요청을 5ms 씩 늦춰(약 5초에 걸쳐 연결) 해결했다. 연결 뒤에는 keep-alive 로 재사용하므로 부하 모양은 그대로다.

## 4. 직접 돌려 보기

```bash
# 단위 테스트 (Docker 불필요)
./gradlew :ieum-api:test --tests "com.hwannee.ieum.orders.service.*" --tests "com.hwannee.ieum.orders.stock.*"

# 통합 테스트 (Docker 필요. MySQL 8.4·Redis 7.4 컨테이너를 테스트가 직접 띄움)
./gradlew :ieum-api:test --tests "com.hwannee.ieum.orders.integration.*"
```

부하 테스트 순서 (IEUM_BE 에서):

1. `docker compose up -d` — 다른 Redis 가 6379 를 쓰고 있으면 `REDIS_PORT=6380 docker compose up -d` 로 띄우고 서버도 같은 `REDIS_PORT` 로
2. `ieum-auth` 를 `ACCESS_TOKEN_TTL=PT4H` 로 기동 (측정이 15분을 넘으면 토큰이 만료된다)
3. `( echo "SET @consumers = 1000; SET @stock = 1000;"; cat scripts/sql/seed-loadtest.sql ) | docker exec -i ieum-mysql …`
4. `k6 run -e USERS=1000 scripts/k6/login-tokens.js` → `scripts/k6/out/tokens.json`
5. `ieum-api` 를 `STOCK_STRATEGY=<전략> SQL_LOG_LEVEL=warn SQL_BIND_LOG_LEVEL=off` 로 기동
6. `k6 run -e REQUESTS=100 scripts/k6/duplicate-burst.js` (사용자당 요청 수. 기준 시나리오는 100 → 총 100,000)
7. `reset-loadtest.sql` 상단의 확인 쿼리 → 결과 기록 → `reset-loadtest.sql`, redis 면 `stock:{id}*` 키 삭제

## 5. 스스로 확인해 볼 질문

- 비관적 락에서 계정 조회를 잠금 앞으로 옮기면 어떤 테스트가 깨지는가? (`OrderConcurrencyScenario` 의 사용자당 1건 단언을 비관적 락에서 반복 실행해 볼 것)
- optimistic 전략은 왜 사용자당 2건이 생기지 않는가? `conditional` 전략으로 같은 부하를 주면 어떻게 될까?
- redis 전략에서 `stock-reserve.lua` 의 멱등키 검사와 중복 검사 순서를 바꾸면 재요청이 어떤 응답을 받는가?
- Reconciliation 주기를 PICKUP 보다 짧게, 혹은 PENDING TTL(30초) 보다 짧게 두면 무엇이 달라지는가?
