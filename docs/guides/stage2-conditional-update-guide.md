# (선택) 조건부 UPDATE 한 문장 구현·측정 가이드

todo 2.2 의 "2단계 낙관적 락 + 재시도 계층" 7번(선택) 을 구현하고 같은 시나리오로 측정하는 절차입니다.
비교 기준은 [2단계 라운드 2](../performance/2026-09-14-stage2-optimistic.md#라운드-2--flush-순서-변경--데드락-재시도-2026-09-17)
(503 574, 충돌 1,894, p99 790ms, ≈ 719 req/s) 입니다.

> 코드 조각은 현재 저장소(Spring Boot 4.1.1 / Java 21 / Hibernate 7.4) 의 실제 클래스 이름과 시그니처에
> 맞춰 썼지만, 컴파일해 본 것은 아닙니다. "함정" 절을 먼저 읽어 두면 시간을 아낍니다.

---

## 0. 이 데이터 포인트가 답하는 질문

라운드 2 의 비용은 두 가지가 섞여 있다.

1. **충돌 검출** — flush 시점의 `UPDATE ... WHERE version = ?` 가 0건이면 실패. 그 전까지 X 락 대기
2. **재시도** — 실패한 트랜잭션을 새 트랜잭션으로 최대 3번 다시 돌림. 충돌 1,894 회 × (`SELECT` 3 + 실패한 `UPDATE` + 롤백),
   커넥션 획득 11,322 회, 그래도 574 건은 답을 못 줌

조건부 UPDATE 는 1 만 남기고 2 를 없앤다. 같은 행의 X 락에 UPDATE 가 줄을 서는 것은 라운드 2 와 같지만,
락을 받은 뒤 `WHERE remaining >= ?` 를 **현재 값** 으로 평가하므로 앞 트랜잭션이 커밋했다고 실패하지 않는다.
version 비교가 아니라 재고 비교이기 때문에, 재고가 남아 있으면 언제나 성공한다.

그래서 기대치는 다음과 같고, 이것이 맞는지 재는 것이 이 라운드의 전부다.

| 항목 | 라운드 2 (optimistic) | 기대 (conditional) | 이 값이 보여 주는 것 |
|---|---|---|---|
| 201 | 100 | 100 | 정합성은 `@Version` 없이도 맞는다 |
| 503 | 574 | **0** | "재고가 있는데 답을 못 준 요청" 이 재시도 상한의 산물이었음 |
| `conflict` / `exhausted` / 커넥션 획득 | 1,894 / 574 / 11,322 | **0 / 0 / 10,002** | 재시도 계층이 한 번도 돌지 않음 |
| DB `version` | 100 | **0** | 벌크 UPDATE 가 `@Version` 을 우회한다는 직접 증거 (naive 와 같음) |
| 롤백된 INSERT | 0 | 0 | UPDATE 가 INSERT 보다 앞이라 FK 데드락 없음 (라운드 2 와 같은 순서) |
| p99 / 처리량 | 790ms / ≈ 719 req/s | ? | 여기가 답. 라운드 2 와 같으면 지연은 직렬화·풀 대기이고 재시도는 무관. 내려가면 재시도의 왕복이 비용이었음 |

`@Version` 이 아닌 것으로 잃는 것도 같이 적는다. 재고 규칙(`remaining >= qty`) 이 엔티티의 `decreaseQuantity` 에서
SQL 로 내려간다. 도메인 객체는 이 경로에서 검증에 참여하지 않고, 영속성 컨텍스트의 엔티티는 UPDATE 이후 stale 이다.
3단계에서 같은 규칙이 Lua 로 옮겨 가므로, 이 라운드는 "규칙이 애플리케이션 밖으로 나가는" 첫 지점이기도 하다.

---

## 1. 리포지토리 — `StoresItemsRepository`

TODO 주석 자리를 메서드로 바꾼다. 복구도 같은 방식으로 원자적으로 둔다.

```java
@Modifying
@Query("""
        update StoresItems s
           set s.remainingQuantity = s.remainingQuantity - :quantity
         where s.id = :id
           and s.remainingQuantity >= :quantity
        """)
int deductIfAvailable(@Param("id") Long id, @Param("quantity") int quantity);

@Modifying
@Query("""
        update StoresItems s
           set s.remainingQuantity = s.remainingQuantity + :quantity
         where s.id = :id
           and s.remainingQuantity + :quantity <= s.initialQuantity
        """)
int restoreIfWithinInitial(@Param("id") Long id, @Param("quantity") int quantity);
```

- 반환값은 갱신된 행 수. `deductIfAvailable` 이 0 이면 재고 부족, `restoreIfWithinInitial` 이 0 이면 상한 초과
- `update versioned StoresItems` 로 쓰지 않는다. `version` 을 올리면 "`@Version` 없이" 라는 데이터 포인트의 의미가 사라지고,
  측정 후 `version` 0 으로 우회를 증명하는 것도 못 한다
- `clearAutomatically` 는 붙이지 않는다. `OrderService.create` 가 이미 읽어 둔 `account`·`item` 이 detach 되면
  `orders.save(new UsersOrders(account, item, qty))` 는 동작하지만 이유 없이 경로가 달라진다. stale 문제는 4절 함정에서 다룬다
- 기존 `overwriteRemainingQuantity` 는 그대로 둔다 (naive 전용)

---

## 2. 전략 — `ConditionalUpdateStockDeduction`

`ieum-api/src/main/java/com/hwannee/ieum/orders/stock/ConditionalUpdateStockDeduction.java`

```java
package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "conditional")
public class ConditionalUpdateStockDeduction implements StockDeductionStrategy {

    private final StoresItemsRepository items;

    public ConditionalUpdateStockDeduction(StoresItemsRepository items) {
        this.items = items;
    }

    @Override
    @Transactional
    public void deduct(Long itemId, int quantity) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        if (item.getRemainingQuantity() < quantity) {
            throw new OrderException.InsufficientStock(item.getRemainingQuantity());
        }
        if (items.deductIfAvailable(itemId, quantity) == 0) {
            throw new OrderException.InsufficientStock();
        }
    }

    @Override
    @Transactional
    public void restore(Long itemId, int quantity) {
        if (items.restoreIfWithinInitial(itemId, quantity) == 0) {
            throw new IllegalStateException("초기 수량을 초과할 수 없습니다.");
        }
    }
}
```

### 2.1 앞의 `findById` 검사를 두는 이유

- `create` 가 `findByUid` 로 이미 같은 엔티티를 영속성 컨텍스트에 올려 두었으므로 `findById` 는 SQL 을 내지 않는다
- 재고 0 을 읽은 요청을 UPDATE 없이 409 로 끝내, **소진 이후의 409 경로를 naive·optimistic 과 같게 맞춘다.**
  이 검사가 없으면 소진 뒤 9,900 건의 UPDATE 도 X 락을 잡고 (WHERE 가 거짓이어도 REPEATABLE READ 에서 PK 로 찾은 행의 락은
  커밋·롤백까지 유지된다) 409 까지 한 줄에 직렬화되어, 라운드 2 와의 p99 차이가 어디서 왔는지 읽을 수 없게 된다
- stale 값이라 "0 을 읽었지만 실제로는 1" 인 경우 409 가 나갈 수 있다. optimistic 도 같은 위치에서 같은 판정을 하고
  재시도 계층이 `InsufficientStock` 을 재시도하지 않으므로 (2단계 가이드 7절), 세 전략의 조건이 같다

### 2.2 두 번째 `InsufficientStock` 에 숫자를 넣지 않는 이유

UPDATE 가 0건이면 "지금 이 순간 `remaining < quantity`" 라는 사실만 안다. 정확한 값을 알려면 `SELECT ... FOR UPDATE` 로
한 번 더 읽어야 하고 (일반 SELECT 는 트랜잭션 시작 시점 스냅샷이라 앞의 stale 값이 다시 나온다), 그 왕복이 이 전략의
장점을 깎는다. `OrderException.InsufficientStock` 에 인자 없는 생성자를 하나 추가한다.

```java
public static final class InsufficientStock extends OrderException {
    public InsufficientStock() {
        super(HttpStatus.CONFLICT, "재고가 부족합니다.");
    }

    public InsufficientStock(int remaining) {
        super(HttpStatus.CONFLICT, "재고가 부족합니다. 남은 수량: " + remaining);
    }
}
```

### 2.3 SQL 순서

`create` 안의 순서는 `SELECT account` → `SELECT item` → `SELECT exists 활성 주문` → **`UPDATE stores_items`** → `INSERT users_orders` → commit.
`@Modifying` 쿼리는 즉시 실행되므로 라운드 2 에서 flush 로 만든 순서(UPDATE 가 INSERT 앞)가 자연히 나온다.
FK 검사의 S 락은 같은 트랜잭션이 이미 X 락을 쥔 행이라 즉시 허용되고, 라운드 1 의 데드락 조건은 성립하지 않는다.

---

## 3. 설정과 주석

- `application.yaml` (ieum-api) — `ieum.stock.strategy` 주석에 `conditional` 추가
  ```yaml
  # 재고 차감 전략: naive | optimistic | conditional | redis (TODO 2.1 의 비교)
  strategy: ${STOCK_STRATEGY:naive}
  ```
- `.env.example` — 같은 줄의 설명에 `conditional(조건부 UPDATE, @Version·재시도 없음)` 추가
- `StockDeductionStrategy` 인터페이스 주석 — `naive | optimistic | redis` 를 네 값으로, "세 구현체" 를 "네 구현체" 로
- `scripts/k6/create-order.js` 상단 주석 — `contention 503` 설명에 "conditional 에서도 0 이어야 한다" 한 줄.
  체크 세 개는 그대로 (503 체크가 0 으로 찍히는 것 자체가 결과다)
- `OrderProperties`·`OrderCreateRetrier`·`ApiExceptionAdvice` 는 손대지 않는다. 재시도 빈은 그대로 경로에 있고
  이 전략에서는 항상 1회에 통과한다. `attempts.used` 가 전부 `le="1"` 인 것이 그 증거가 된다

---

## 4. 테스트 — `ConditionalUpdateStockDeductionTest`

`ieum-api/src/test/java/com/hwannee/ieum/orders/stock/ConditionalUpdateStockDeductionTest.java`.
`OrderServiceTest` 와 같은 순수 Mockito 스타일 (Docker 없이). `StoresItems` 는 `OrderServiceTest` 가 만드는 방식을 따라
생성자 + `ReflectionTestUtils.setField(item, "id", ITEM_ID)` 로 만든다.

| 케이스 | given | then |
|---|---|---|
| 재고가 있으면 조건부 UPDATE 한 번으로 끝난다 | `findById` → remaining 5, `deductIfAvailable(ITEM_ID, 1)` → 1 | 예외 없음. `deductIfAvailable` 1회 호출. **엔티티의 `remainingQuantity` 는 5 그대로** (도메인 메서드를 타지 않음) |
| 스냅샷이 0 이면 UPDATE 없이 `InsufficientStock` | remaining 0 | `InsufficientStock`, `deductIfAvailable` `never()` |
| 스냅샷은 충분한데 UPDATE 가 0건이면 `InsufficientStock` | remaining 1, `deductIfAvailable` → 0 | `InsufficientStock` (메시지에 숫자 없음) |
| 상품이 없으면 `ItemNotFound` | `findById` → empty | `ItemNotFound`, `deductIfAvailable` `never()` |
| 복구 상한 초과면 `IllegalStateException` | `restoreIfWithinInitial` → 0 | 예외 |
| 복구 성공 | → 1 | 예외 없음 |

`OrderServiceTest`·`OrderCreateRetrierTest` 는 전략을 모킹하므로 바뀌지 않는다.
실행: `./gradlew :ieum-api:test --tests "com.hwannee.ieum.orders.*"` (JAVA_HOME 은 azul-21).

---

## 5. 측정 절차

2단계 가이드 6절과 같다. 다른 점만 적는다.

1. `.env` 에 `STOCK_STRATEGY=conditional`, `SQL_LOG_LEVEL=warn`, `SQL_BIND_LOG_LEVEL=off`. 재시도 설정은 기본값 그대로 (돌지 않아야 정상)
2. `reset-loadtest.sql` 상태 확인 — 라운드 2 뒤에 실행해 두었다 (재고 100 · 주문 0 · `version` 0 · AUTO_INCREMENT 1)
3. 두 서버 기동 (`bootJar` + `java -jar`, 라운드 2 와 같게). 예약 1건을 `SQL_LOG_LEVEL=debug` 로 먼저 보내
   `update stores_items ... where id=? and remaining_quantity>=?` 가 `insert into users_orders` 앞에 찍히고
   `version` 컬럼이 SET 절에 없는지 확인한 뒤 `warn` 으로 되돌리고 `reset-loadtest.sql`
4. **행 락 통계 스냅샷 (이 라운드에서 새로 추가).** k6 직전과 직후에 한 번씩
   ```sql
   SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%';
   ```
   `Innodb_row_lock_waits` 와 `Innodb_row_lock_time`(ms) 의 차이가 이 라운드의 "재고 한 줄에 줄 선 횟수와 시간" 이다.
   라운드 2 에는 이 값이 없으므로 이번에 처음 남기고, 3단계에서 0 에 가까워지는지 본다
5. 스크랩 루프 (2단계 가이드 4.4, 파일명은 `stage2c-` 로) → `k6 run scripts/k6/create-order.js` → 루프 정지 → 최종 스냅샷
6. DB 확인 쿼리 (라운드 2 와 같은 넷)
   ```sql
   SELECT order_state, COUNT(*) AS cnt, SUM(quantity) AS qty FROM users_orders o JOIN stores_items i ON i.id = o.store_item_id
    WHERE i.uid = '33333333-3333-3333-3333-333333333333' GROUP BY order_state;
   SELECT initial_quantity, remaining_quantity, version FROM stores_items WHERE uid = '33333333-3333-3333-3333-333333333333';
   SELECT MAX(id) AS max_id, COUNT(*) AS cnt, MAX(id) - COUNT(*) AS rolled_back_inserts FROM users_orders;
   SELECT MIN(created_at), MAX(created_at) FROM users_orders;
   SHOW ENGINE INNODB STATUS\G   -- LATEST DETECTED DEADLOCK 절이 여전히 없어야 한다
   ```
7. `reset-loadtest.sql`

### 5.1 기록할 것

`performance/<날짜>-stage2-conditional-update.md`. 라운드 2 의 "결과 요약" 표와 같은 행에 이 라운드 값을 넣고,
naive (09-16) / optimistic 라운드 2 / conditional 세 열 비교표를 둔다. 추가 행:

| 항목 | 출처 |
|---|---|
| `Innodb_row_lock_waits` / `Innodb_row_lock_time` 증가분, 대기 1회 평균 | 4번 스냅샷 차이 |
| 커넥션 획득 횟수 (10,002 이어야 함 — 재시도 0 의 증거) | `hikaricp_connections_acquire_seconds_count` |
| `order_create_attempts_used_bucket{le="1"}` = 100 | Prometheus |

### 5.2 해석에 넣을 것

- **503 이 0 인가.** 0 이면 라운드 2 의 574 는 "상한 3 의 낙관적 재시도" 가 만든 값이지 재고 경합 자체의 값이 아니었다.
  0 이 아니면 재시도 빈이 잡는 예외가 다른 경로에서 나온 것 → API 로그의 예외 타입 확인
- **p99·처리량이 라운드 2 와 어떻게 다른가.** 두 전략 모두 X 락에 줄을 서는 것은 같고, 다른 것은 "줄 끝에서 실패해 다시 줄 서느냐" 뿐이다.
  - p99 가 거의 같으면 지연의 정체는 행 직렬화 + HikariCP 대기(`pending` ≈ 80) 이고 재시도는 지연에 기여하지 않았다
  - p99 가 내려가면 라운드 2 의 1,320 회 추가 커넥션 획득(재시도) 이 풀 앞의 줄을 길게 만들고 있었다
  - `acquire` 평균은 88ms (라운드 2) 보다 내려갈 가능성이 크다. 실패 시도가 풀에서 사라지기 때문
- **행 락 대기 시간이 얼마인가.** `Innodb_row_lock_time ÷ waits` 가 X 락 보유 시간(UPDATE → INSERT → commit) 의 실측이다.
  라운드 2 해석에서 "백오프 `[0, 10ms × 시도]` 가 X 락 보유 시간보다 짧았을 것" 이라고 추정만 했던 값을 여기서 확인한다
- **`version` 0.** 정합성이 맞는데 `version` 이 안 움직였다는 것이 "조건부 UPDATE 는 `@Version` 을 쓰지 않고도 lost update 를 막는다" 의 증거.
  단, 같은 행을 엔티티 더티 체킹으로 고치는 다른 경로(현재는 `restore` 가 아니라 3단계 재고 조정 API 등) 와 섞이면
  그 경로의 version 검사가 이 UPDATE 를 못 본다. `stores_items.remaining_quantity` 는 **한 가지 방식으로만 써야 한다** 는 규칙이 여기서 생긴다
- **무엇이 남는가.** 재고 한 줄의 X 락 직렬화(처리량 상한), 규칙이 SQL 로 내려간 것, 중복 활성 예약 검사의 틈(변함없음).
  이 셋이 3단계에서 Redis 가 가져가야 할 것의 목록이다

### 5.3 ADR-0003

- 결과 표의 `(선택) 조건부 UPDATE` 행을 채운다. 재시도 열은 "없음 (충돌 0)"
- 결정 절에 한 단락: "낙관적 락 비용의 분해" — 503 574 중 얼마가 재시도 상한의 산물이었는지, p99 차이가 얼마인지, 행 락 대기 실측
- 상태 줄의 날짜에 이번 라운드 추가

### 5.4 todo

- 2.2 의 `(선택) 7.` 을 체크하고 결과 한 줄 + performance 링크
- 2.1 의 "(선택) 2 와 3 사이에 조건부 UPDATE" 항목에 완료 날짜

---

## 6. 함정

- **`decreaseQuantity` 를 같이 부르지 않는다.** 엔티티를 고치면 커밋 시 더티 체킹이 `UPDATE ... SET remaining=?, version=? WHERE version=?`
  를 한 번 더 내보내 이중 차감이 되거나 (스냅샷 값 − qty 로 덮어씀 → naive 와 같은 lost update), version 충돌로 실패한다.
  이 전략에서 엔티티는 읽기 전용이다. 단위 테스트의 "엔티티 값이 그대로" 검증이 이 규칙을 고정한다
- **`@Modifying` 은 트랜잭션 안에서만 실행된다.** `deduct` 의 `@Transactional` (REQUIRED) 이 `create` 의 트랜잭션에 참여하므로 문제없지만,
  전략을 트랜잭션 밖에서 단독 호출하면 `TransactionRequiredException`
- **UPDATE 이후 영속성 컨텍스트의 `item` 은 stale 이다.** `remainingQuantity` 는 차감 전 값, `version` 은 0. 같은 트랜잭션에서
  그 값을 응답에 쓰면 틀린다. `OrderResponse` 는 주문만 담으므로 지금은 해당 없지만, 응답에 남은 재고를 넣을 계획이 생기면 `clearAutomatically`
  또는 별도 조회가 필요하다
- **`InsufficientStock` 은 재시도 대상이 아니다.** 두 번째 검사에서 0건이면 그대로 409. 재시도 빈이 `ApiException` 을 통과시키는 것은 2단계와 같다
- **락 대기 타임아웃.** 100 VU 가 한 행에 줄을 서도 보유 시간이 ms 단위라 `innodb_lock_wait_timeout` (50s) 근처에도 가지 않는다.
  만약 `PessimisticLockingFailureException` 이 보이면 다른 세션이 행을 쥐고 있는 것 (예: 확인 쿼리를 트랜잭션 안에서 열어 둔 클라이언트)
- **REPEATABLE READ 에서 UPDATE 는 현재 값을 본다.** 앞의 `findById` 가 읽은 스냅샷과 UPDATE 가 평가하는 값은 다를 수 있고, 그것이 의도다.
  "스냅샷은 1 인데 UPDATE 가 0건" 이 정상 경로이며 그때 두 번째 `InsufficientStock` 이 나간다
- **`version` 이 0 이 아니면** 어딘가에서 엔티티를 더럽히고 있다. 라운드 후 첫 확인 항목
- **`contention 503` 체크를 지우지 않는다.** 0 이 찍혀야 "재시도 계층이 새지 않았다" 를 k6 출력만으로 읽을 수 있다

---

## 7. 커밋 단위

파일당 하나씩.

1. `feat: add conditional deduct and restore queries to StoresItemsRepository`
2. `feat: add InsufficientStock constructor without remaining count`
3. `feat: add ConditionalUpdateStockDeduction strategy without version or retry`
4. `docs: list conditional strategy in application.yaml` / `.env.example` / `StockDeductionStrategy` 주석 / k6 주석 (각각 별도)
5. `test: add ConditionalUpdateStockDeductionTest`
6. `docs: record conditional update load test results` / ADR-0003 / todo (각각 별도)
