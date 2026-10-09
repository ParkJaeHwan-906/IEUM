# ADR-0004. 예약 생성 경로 — 비관적 락, 낙관적 락, Redis Lua 통합 판정을 같은 중복 요청 부하로 비교한다

- 상태: **결정** (2026-10-09)
- 날짜: 2026-10-09
- 관계: [ADR-0003](./0003-stock-deduction-concurrency.md) 의 결정(`redis` 채택) 을 이어받아, 남아 있던 세 가지 틈
  (중복 활성 예약 검사의 경합, 멱등키 미구현, 거절 요청의 DB 커넥션 점유) 을 닫는다. ADR-0003 이 비교하지 않았던 비관적 락을 추가한다.
  측정 기록은 [performance/2026-10-09-duplicate-burst.md](../performance/2026-10-09-duplicate-burst.md)

## 배경

ADR-0003 의 시나리오는 "재고 100 / 소비자 10,000 명이 1회씩" 이었다. 소비자 한 명은 한 번만 요청하므로 **같은 사용자의 요청이 겹치는 경우** 를 재지 않았다.
실제로는 사용자가 버튼을 여러 번 누르고, 앱이 재시도한다. 이때 지켜야 할 불변식이 하나 더 생긴다.

1. **초과 예약 없음** — `initial = remaining + 활성 수량 + 픽업 완료 수량`
2. **사용자당 상품당 활성 예약 1건** — 같은 사용자의 동시 요청 두 개가 모두 "활성 예약 없음" 을 읽고 둘 다 성공하면 깨진다
3. **같은 Idempotency-Key 는 한 번만 처리** — 재시도가 새 예약이 되면 안 된다

2 는 ADR-0003 의 모든 DB 전략에서 `existsByAccountAndItemInStates` 조회 후 INSERT 라 틈이 있다고 보았다 (todo 2.2 "동시 요청 사이의 틈은 남아 있음"). 아래 2 절처럼 `optimistic` 은 실측에서 닫혀 있었다.
3 은 헤더만 받고 처리하지 않았다.

## 결정

세 구현을 모두 두고 `STOCK_STRATEGY` 로 갈아 끼운다. 운영 경로는 ADR-0003 과 같이 **`redis`** 이고, 이번에 그 경로를 완성했다.

### 1. 비관적 락 (`pessimistic`) — `PessimisticLockStockDeduction`

`SELECT … FOR UPDATE` 로 상품 행을 잠근다. 핵심은 **잠금이 트랜잭션의 첫 문장이어야 한다** 는 것이다.

- MySQL REPEATABLE READ 의 스냅샷(read view) 은 트랜잭션 시작이 아니라 **첫 일반 SELECT** 에서 만들어진다. 잠금 읽기(`FOR UPDATE`) 는 스냅샷을 만들지 않는다
- 계정이나 상품을 일반 SELECT 로 먼저 읽고 나서 잠그면, 잠금을 기다리는 동안 앞 트랜잭션이 커밋한 주문이 **이 트랜잭션의 스냅샷에는 보이지 않는다**. 그러면 잠금을 쥔 채로도 중복 검사가 틀린다
- 그래서 `OrderService.create` 는 `stock.locksItemRow()` 가 참이면 `findByUidForUpdate` 를 맨 앞에서 부르고, 그 뒤의 계정 조회·멱등키 조회·중복 검사가 잠금 획득 이후의 스냅샷을 본다
- 상품 행 X 락을 INSERT 보다 먼저 잡으므로 ADR-0003 라운드 1 의 FK 데드락(INSERT 의 S 락 → UPDATE 의 X 락) 은 구조적으로 생기지 않는다
- 대가: 재고가 없는 요청, 중복 요청까지 **모두** 같은 행 잠금 앞에 줄을 선다. 거절할 요청도 잠금을 받아야 거절할 수 있다
- `jakarta.persistence.lock.timeout` 힌트는 넣지 않았다. Hibernate MySQL 방언은 `NOWAIT`·`SKIP LOCKED` 만 SQL 로 옮기고 밀리초 대기는 무시한다 (실측: `FOR UPDATE OF si1_0` 만 나가고 `innodb_lock_wait_timeout` 은 50초 그대로). 효과 없는 어노테이션을 남기지 않는다.
  잠금 대기는 커넥션 풀(20) 이 상한을 정한다 — 한 행을 기다리는 트랜잭션은 많아야 19개다

### 2. 낙관적 락 (`optimistic`) — 코드 변경 없음

ADR-0003 의 구현(`@Version` + flush 선행 + 재시도 계층) 을 그대로 다시 쟀다.

측정 전에는 "중복 검사는 잠금 없이 읽으므로 틈이 남는다" 고 예상했는데, **실측은 사용자당 2건 이상 0** 이었다. 틀린 예상이었고 이유는 이렇다.

- 중복 검사(`existsByAccountAndItemInStates`) 와 상품 조회는 같은 트랜잭션의 같은 REPEATABLE READ 스냅샷을 본다
- 같은 사용자의 다른 요청이 그 스냅샷 이후에 커밋했다면, 그 커밋은 **같은 상품 행의 `version` 도 올렸다**
- 그러므로 이 트랜잭션의 `UPDATE … WHERE version = ?` 는 반드시 0행 → 충돌 → 새 트랜잭션으로 재시도 → 새 스냅샷에서 중복이 보여 409

즉 틈을 닫는 것은 중복 검사가 아니라 **모든 예약이 같은 행의 version 을 올린다는 사실** 이다. 재고를 상품 행 밖(Redis·별도 테이블) 으로 옮기거나,
`@Version` 없는 조건부 UPDATE(`conditional`) 로 바꾸는 순간 이 우연한 보장은 사라진다. 통합 테스트는 이 전략에 사용자당 1건을 단언하지 않는다.
재시도 상한에 걸린 요청이 503 이 되므로 "모든 재고가 팔린다" 도 보장되지 않기 때문이다.

### 3. Redis Lua 통합 판정 (`redis`) — `RedisOrderCreator` + `RedisStockDeduction`

DB 전략과 경로 자체가 다르므로 컨트롤러 앞단을 `OrderCreator` 인터페이스로 나눴다. DB 전략은 `OrderCreateRetrier`, `redis` 는 `RedisOrderCreator` 가 빈으로 올라간다.

```text
① 판매 조건   ItemSaleCache (uid → itemId·마감 시각·영업 종료, TTL 5s)    DB 커넥션 없음
② stock-reserve.lua   멱등키 → 활성 사용자 SET → 재고 → DECRBY + SADD + SET idem PENDING EX 30
     REPLAY(orderId) → DB 에서 그 주문을 돌려줌 / REPLAY(PENDING) → 409 처리 중
     DUPLICATE → 409 / SOLD_OUT → 409                                      여기까지 DB 커넥션 없음
③ OrderService.place   INSERT users_orders 만 (stores_items 는 읽기만, 잠그지 않음)
     실패 → stock-compensate.lua (INCRBY + SREM + DEL idem)
     unique(account, idempotency_key) 위반 → 보상 후 DB 의 주문으로 재생
④ 커밋 뒤   SET idem orderId EX 1d
```

- **Lua 를 쓰는 이유.** 재고 하나만이면 `DECRBY` 후 음수면 되돌리는 것으로 충분하다. Lua 가 필요한 것은 "멱등키 확인 → 중복 확인 → 재고 확인 → 세 키 쓰기" 를 한 원자 연산으로 묶을 때다.
  MULTI/EXEC 는 읽은 값으로 분기할 수 없고, WATCH 는 Redis 판 `@Version` 이라 경합에서 재시도가 폭주한다
- **키는 해시 태그로 한 슬롯에.** `stock:{id}`, `stock:{id}:active`, `stock:{id}:idem:{uid}:{key}`, `stock:{id}:restored:{orderId}`. Redis Cluster 로 옮겨도 한 스크립트가 다루는 키가 같은 슬롯에 있다.
  대가로 멱등키가 상품 단위라 "같은 키를 다른 상품에" 는 Lua 가 못 잡고, DB unique 위반으로 잡혀 보상 후 409 가 된다
- **PENDING 은 30초.** 판정 후 커밋 전에 서버가 죽으면 표식이 남는다. 24시간이면 그동안 같은 키 재시도가 전부 "처리 중" 이 되므로 짧게 두고, 확정 시 주문 id 와 1일 TTL 로 덮는다
- **확정 실패는 삼킨다.** 커밋 뒤의 `SET idem orderId` 가 실패해도 주문은 이미 있다. 여기서 예외를 던지면 바깥의 보상이 커밋된 주문의 재고를 되돌린다. PENDING 이 만료된 뒤의 재시도는 DB unique 로 재생된다

### 4. 복구는 주문당 1회 — `stock-release.lua`

`StockDeductionStrategy.restore(Long itemId, int quantity)` 를 `restore(UsersOrders order)` 로 바꿨다. DB 전략은 상태 전이와 재고 증가가 한 트랜잭션이라 상태 가드가 곧 1회 보장이다.
`redis` 는 커밋 뒤 `SET stock:{id}:restored:{orderId} NX` 가 성공할 때만 `INCRBY` + `SREM` 한다. 워커 중복 실행, 크래시 후 재시도, 취소와 만료의 겹침 모두 1회로 끝난다.
복구 실패는 예외로 올리지 않고 `stock.redis.event{event=release_failed}` 로 세며 Reconciliation 이 정정한다 (전에는 `afterCommit` 예외가 클라이언트 500 이 되었다).
픽업 완료는 재고를 복구하지 않고 활성 사용자 SET 에서만 뺀다 (`settle`).

### 5. 워밍업은 DB 주문 상태로

키가 없으면(Redis 재기동·유실) `stores_items.remaining_quantity` 투영이 아니라 `initial − Σ(활성 + 픽업 완료)` 와 활성 주문의 사용자 uid 로 `SET NX` + `SADD` 한다 (`stock-warmup.lua`).
투영이 stale 이어도 원장이 틀리게 복원되지 않는다. 활성 SET 도 함께 복원해야 재기동 직후 중복 예약 틈이 열리지 않는다.

### 6. Reconciliation — 두 번 연속 같은 어긋남만 정정

60초마다 상품별로 DB 기대값(재고·활성 사용자 집합) 과 Redis 를 대조한다. 처음 보는 어긋남은 기록만 하고, **다음 주기에 (DB 기대값, Redis 값, 초과·누락 사용자) 가 완전히 같을 때만** 정정한다.

- 대조 순간에는 ② 는 지났지만 ③ 이 아직 커밋되지 않은 정상 예약이 있다. 이것도 "어긋남" 으로 보인다
- 처음에는 "차이 값이 두 번 같으면" 으로 짰다가 버렸다. 트래픽이 꾸준하면 진행 중 예약 수가 우연히 두 번 같을 수 있고, 그때 정정하면 재고를 더 풀어 **초과 예약을 만든다**
- 기대값과 Redis 값이 60초 동안 둘 다 그대로라면 그 상품에서는 아무 일도 없었고, 남은 차이는 진행 중 예약이 아니라 유실이다. 트래픽이 계속되는 상품은 정정이 미뤄지지만 정합성이 우선이다
- 정정은 `SET` 이 아니라 `INCRBY 차이` 로 한다. 대조와 정정 사이에 들어온 차감을 덮어쓰지 않는다
- 같은 Job 이 `stores_items.remaining_quantity` 투영을 기대값으로 갱신하고, 만료 인덱스에서 빠진 `READY_FOR_PICKUP` 주문을 다시 등록한다

### 7. 멱등키는 모든 전략에서 — `users_orders.idempotency_key` + unique(user_account_id, idempotency_key)

DB 전략은 트랜잭션 안에서 키로 먼저 조회해 있으면 그 주문을 돌려주고, 동시에 같은 키가 들어와 unique 위반이 나면 롤백 후 기존 주문을 돌려준다.
같은 키를 다른 상품에 쓰면 409 `IdempotencyKeyReused`. 재요청 응답은 최초와 같은 201 과 같은 본문이다.

## 검증

- 단위 테스트: `OrderServiceTest`(13), `RedisStockDeductionTest`(8) 등 53건
- **Testcontainers 통합 테스트** (`orders/integration/`, MySQL 8.4 + Redis 7.4, Docker 없으면 건너뜀): 세 전략이 같은 `OrderConcurrencyScenario` 를 상속
  - 사용자 40 명 × 10 회 동시 요청, 재고 25 — 초과 예약 없음, 원장 = 재고 − 성공 수. 비관적·redis 는 성공 정확히 25 건, 사용자당 1 건까지 단언
  - 같은 멱등키 8 개 동시 → 주문 1 건, 재고 1 회 차감, 이후 재요청은 같은 주문
  - 같은 키를 다른 상품에 → 거절, 두 번째 상품 재고 그대로
  - 취소 → 재고 복구 → 같은 사용자 재예약 가능
  - redis 전용: 복구 두 번 → 1 회만, 키 유실 → DB 주문 상태로 워밍업해 중복을 여전히 거절, 두 번 연속 같은 어긋남만 정정, 사이에 값이 바뀌면 정정 안 함
- 부하 측정: 아래 "측정 결과"

## 측정 결과

1,000 명 × 100 요청(동시 4개씩) = 100,000, 재고 1,000. 전문은 [performance/2026-10-09-duplicate-burst.md](../performance/2026-10-09-duplicate-burst.md).

| | pessimistic | optimistic | redis |
|---|---|---|---|
| 201 / 사용자당 2건 이상 | 1,000 / 0 | 1,000 / 0 | 1,000 / 0 |
| 503 | 0 | 3,904 (3.9%) | 0 |
| 처리량 | 319 req/s | 1,082 req/s | **3,672 req/s** |
| 서버 측 평균 409 | 621ms | 150ms | **50ms** |
| 커넥션 획득 | 100,002 | 111,836 | **1,011** |
| 행 락 대기 | 99,999 | 16,635 | **0** |

세 전략 모두 정합성(초과 예약 0, 사용자당 1건) 은 맞았다. 차이는 **거절을 얼마나 싸게 하느냐** 다. 비관적 락은 거절도 잠금을 받아야 하고,
낙관적 락은 재시도 상한에서 답을 못 주는 요청이 생기며, redis 는 거절이 DB 에 닿지 않는다. 결정은 ADR-0003 과 같이 `redis` 를 유지한다.
Redis 를 운영할 수 없는 환경이면 `pessimistic` 이 가장 단순하게 정합성을 지키지만 상품 하나의 처리량이 행 잠금 보유 시간(≈ 3ms) 에 묶인다.

## 남은 것

- 사용자당 활성 SET 은 상품별이라 "같은 사용자가 같은 키를 24시간 뒤 다른 활성 주문이 있는 상태에서 재사용" 하는 극단적 경우 보상의 `SREM` 이 활성 사용자를 지운다. Reconciliation 이 두 주기 뒤 되돌린다
- 다중 인스턴스에서 `ItemSaleCache` 는 인스턴스별이다. 마감 시각·영업 종료 변경은 최대 TTL(5초) 늦게 반영된다
- Reconciliation 은 아직 단일 인스턴스 가정. 여러 인스턴스가 동시에 정정하면 `INCRBY` 가 겹친다 → 리더 선출 또는 상품별 락 (V3)
