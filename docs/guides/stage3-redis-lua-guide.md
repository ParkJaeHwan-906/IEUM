# 3단계 Redis Lua 재고 차감 구현·측정 가이드

todo 2.1 동시성 제어의 3번, 2.2 의 "Redis Lua Script 기반 원자적 재고 차감" 을 구현하고 같은 시나리오로 측정하는 절차입니다.
비교 기준은 [(선택) 조건부 UPDATE](../performance/2026-09-17-stage2-conditional-update.md)
(503 0, 충돌 0, 행 락 대기 118회 · 평균 117ms, 재고 소진 0.76초, p99 669ms, ≈ 758 req/s) 입니다.

> 코드 조각은 현재 저장소(Spring Boot 4.1.1 / Spring Data Redis / Lettuce / Java 21) 의 실제 클래스 이름과 시그니처에
> 맞춰 썼지만, 컴파일해 본 것은 아닙니다. "함정" 절을 먼저 읽어 두면 시간을 아낍니다.

---

## 0. 이 단계가 답하는 질문

조건부 UPDATE 까지 오면서 남은 것은 셋이었다 (ADR-0003 "낙관적 락 비용의 분해").

1. **재고 한 행의 X 락 직렬화** — 성공 트랜잭션 100 개가 평균 7.6ms 간격으로 한 줄로 커밋. 행 락 대기 118회
2. 재고 규칙이 애플리케이션 밖(SQL) 으로 나간 것
3. 중복 활성 예약 검사의 틈 (DB 조회 기반)

3단계는 1 을 없앤다. 재고 원장을 `stores_items.remaining_quantity` 에서 Redis 키 `stock:{itemId}` 로 옮기고,
"확인 후 차감" 을 Lua 스크립트 한 번으로 끝낸다. Redis 는 스크립트를 단일 스레드로 실행하므로 GET 과 DECRBY 사이에
다른 요청이 끼어들 수 없고, 행 락도 커넥션 점유도 없다. **예약 생성 트랜잭션은 `stores_items` 를 더 이상 UPDATE 하지 않는다.**

2 는 그대로다. 규칙이 SQL 에서 Lua 로 자리만 옮긴다. 3 은 이 단계에서 닫지 않는다 (README V1 의 "하나의 원자적 연산" 은
중복 검사·멱등 키까지 Lua 에 넣지만, 이번 라운드는 재고 차감만 옮겨야 앞 라운드와 한 변수만 다르다).

그 대가로 새 문제가 생긴다. **Redis 차감과 DB INSERT 가 한 트랜잭션이 아니다.** Redis 에서 뺐는데 DB 커밋이 실패하면
재고가 새고, 취소로 DB 는 CANCELED 인데 Redis INCRBY 가 안 되면 또 샌다. 이 라운드는 그 창을 어디에 두고 어떻게 메우는지를
코드로 못 박고, 부하 중에 그 창이 실제로 몇 번 열렸는지 (보상 복구 횟수) 를 같이 잰다.

| 항목 | conditional | 기대 (redis) | 이 값이 보여 주는 것 |
|---|---|---|---|
| 201 | 100 | 100 | Lua 의 원자성으로 정합성이 맞는다 |
| 503 / 충돌 / 재시도 | 0 / 0 / 0 | 0 / 0 / 0 | 재시도 계층은 여전히 통과만 한다 |
| DB `version` | 0 | 0 | `stores_items` 를 건드리지 않는다 |
| DB `remaining_quantity` | 0 | **100 (그대로)** | DB 열은 더 이상 원장이 아니다. 투영은 Reconciliation 의 몫 |
| Redis `stock:{id}` | — | **0** | 원장 |
| 불변식 | `initial = DB remaining + active` | `initial = Redis stock + active` | 검사 위치가 바뀐다 |
| `Innodb_row_lock_waits` 증가분 | 118 | **0** | 재고 행 직렬화가 사라졌다는 직접 증거 |
| 커넥션 획득 횟수 | 10,000 | 10,000 | 요청당 DB 트랜잭션 1개는 그대로 (SELECT 3 + INSERT) |
| 보상 복구 (`compensated`) | — | **0** | 부하 중 Redis↔DB 창이 열리지 않았음. 0 이 아니면 그 수만큼 Redis 로 되돌아간 재고 |
| 재고 소진까지 | 0.76초 | ? (한 자릿수 이하 ms 단위 간격) | 성공 100 건이 더 이상 한 줄로 커밋되지 않는다 |
| p99 / 처리량 | 669ms / ≈ 758 req/s | ? | 여기가 답. naive 633ms 근처에 머물면 이 부하의 지연은 전부 VU 100 / 풀 20 대기이고 재고 전략은 끝난 문제. 내려가면 행 락 대기가 `acquire` 안에 숨어 있었던 것 |
| Redis `evalsha` 호출 수 / 평균 시간 | — | 10,000 + 워밍업 / 수십 µs | Redis 가 병목이 아님 |

---

## 1. 설계 결정 — 구현 전에 정할 것

### 1.1 원장은 Redis, DB 열은 투영

`redis` 전략에서 `stores_items.remaining_quantity` 는 예약 경로에서 읽지도 쓰지도 않는다. 이유는 하나다. 같은 행을
UPDATE 하는 순간 X 락 직렬화가 돌아오고, 그러면 Redis 는 "빨리 실패하는 캐시" 이상이 되지 못한다.

따라서 이 전략에서는

- `deduct` — Lua 로 Redis 만 차감
- `restore` — Redis 만 INCRBY
- DB 열은 stale 상태로 남는다. `GET /api/items/{itemUid}` 의 `remainingQuantity` 도 stale 이다. 이 라운드에서는 그대로 두고
  todo 에 남긴다 (조회 경로가 Redis 를 보게 하거나, Reconciliation Job 이 주기적으로 DB 열을 Redis 값으로 덮어쓰거나)
- 부하 테스트의 불변식 검사는 SQL 만으로 못 하고 `GET stock:{id}` 와 합쳐서 본다 (7절)

### 1.2 키

`stock:{itemId}` = 남은 수량 (문자열 정수). TTL 없음. Compose 의 Redis 가 이미 `--appendonly yes`, `--maxmemory-policy noeviction`
이라 로컬에서도 원장으로 둘 수 있다. `{itemId}` 는 내부 PK 다. 키는 외부에 노출되지 않고, `create` 가 이미 엔티티를 들고 있어
uid → id 변환이 공짜다.

### 1.3 워밍업

키가 없으면 스크립트가 `-2` 를 돌려주고, 전략은 DB 의 `remaining_quantity` 로 `SET NX` 한 뒤 스크립트를 한 번 더 실행한다.
`NX` 라서 100 VU 가 동시에 워밍업해도 한 번만 쓴다. 상품 등록 시에는 커밋 후 `SET` 으로 미리 넣는다 (4절).

부하 테스트에서는 워밍업 경로를 측정에서 빼기 위해 라운드 전에 `redis-cli` 로 직접 `SET` 한다 (7절). 워밍업이 부하 중에
돌면 첫 요청 무리의 지연에 DB 조회가 섞인다. `warmup` 카운터가 0 인지가 확인 항목이다.

### 1.4 트랜잭션 경계 — 차감은 즉시, 보상은 롤백 후, 복구는 커밋 후

`OrderService.create` 는 `@Transactional` 이고 그 안에서 `stock.deduct` → `orders.save` 순이다. Redis 는 이 트랜잭션에
참여하지 않으므로 세 시점을 명시적으로 나눈다.

| 동작 | 시점 | 이유 |
|---|---|---|
| `deduct` 의 Lua 실행 | 호출 즉시 (트랜잭션 안) | 결과(성공/부족) 로 INSERT 여부를 결정해야 하므로 |
| `deduct` 의 보상 INCRBY | `afterCompletion(STATUS_ROLLED_BACK)` | INSERT 나 커밋이 실패했을 때만. `InsufficientStock` 으로 롤백되는 경우는 차감이 없었으므로 등록하지 않는다 |
| `restore` 의 INCRBY | `afterCommit` | `cancel` 이 DB 에서 실패(상태 전이 예외, `UsersOrders.version` 충돌) 하면 Redis 도 건드리지 않아야 한다. 트랜잭션 안에서 바로 INCRBY 하면 동시 취소 두 건이 둘 다 복구해 이중 복구가 된다 |

이 배치로 남는 창은 둘이다. (a) Lua 차감 후 커밋 전에 프로세스가 죽으면 보상이 안 돌아 재고가 샌다. (b) 커밋 후 `afterCommit`
전에 죽으면 복구가 안 된다. 둘 다 "DB 주문 상태로 Redis 값을 다시 계산하면 잡히는" 종류이고, 그것이 Reconciliation Job (todo 2.2) 이다.
이 라운드에서는 창의 위치를 코드와 문서에 적고 `compensated` 카운터로 부하 중 열린 횟수를 재는 데서 멈춘다.

`STATUS_UNKNOWN` (커밋 결과를 모름, 예: 커밋 중 DB 연결 끊김) 에서는 보상하지 않는다. 주문이 있을 수도 있어 되돌리면 이중 재고가 된다. 역시 Reconciliation 의 몫.

### 1.5 복구 멱등성은 이번에 넣지 않는다

todo 의 "Lua restore: `restored:{orderId}` SETNX" 는 Expiry Worker 가 같은 주문을 두 번 처리할 때를 위한 것이다.
지금 `restore(Long itemId, int quantity)` 에는 `orderId` 가 없고, 취소 경로는 `afterCommit` 배치만으로 "주문당 1회" 가 성립한다
(DB 전이가 한 번만 성공하므로). Expiry Worker 를 만들 때 시그니처에 `orderId` 를 더하며 같이 넣는다. 지금 넣으면 네 전략과
테스트를 전부 손대야 하고, 이 라운드에서 재는 것과 무관하다.

같은 이유로 `INCRBY` 에 상한(`initialQuantity`) 검사도 넣지 않는다. 조건부 UPDATE 의 `restoreIfWithinInitial` 이 하던 일인데,
Redis 에는 initial 이 없다. 상한 위반은 Reconciliation 이 불변식으로 잡는다. 이것도 "남는 것" 에 적는다.

---

## 2. Lua 스크립트 — `stock-deduct.lua`

`ieum-api/src/main/resources/redis/stock-deduct.lua`

```lua
local remaining = tonumber(redis.call('GET', KEYS[1]))
if remaining == nil then
    return -2
end
if remaining < tonumber(ARGV[1]) then
    return -1
end
return redis.call('DECRBY', KEYS[1], ARGV[1])
```

- `KEYS[1]` = `stock:{itemId}`, `ARGV[1]` = 수량. 키를 `KEYS` 로 넘기는 것은 Cluster 로 갈 때 슬롯 판정을 위해서다. 지금은 단일 노드지만 습관을 들인다
- 키가 없으면 `GET` 은 Lua 의 `false` 를 돌려주고 `tonumber(false)` 는 `nil` 이다. 그래서 `== nil` 검사가 맞는다
- 반환은 정수 세 종류: `-2` 키 없음, `-1` 재고 부족, `0 이상` 차감 후 남은 수량. Redis 는 Lua 정수를 integer reply 로 돌려주므로 Java 에서 `Long` 으로 받는다
- 복구는 스크립트가 필요 없다. `INCRBY` 한 명령이 원자적이다
- Spring Data Redis 는 `EVALSHA` 를 먼저 보내고 `NOSCRIPT` 면 `EVAL` 로 올린 뒤 다시 보낸다. Redis 재기동 후 첫 호출만 두 번 왕복하고 이후는 SHA 만 간다

---

## 3. 전략 — `RedisStockDeduction`

`ieum-api/src/main/java/com/hwannee/ieum/orders/stock/RedisStockDeduction.java`. 기존 스텁과 TODO 주석을 아래로 바꾼다.

```java
package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "redis")
public class RedisStockDeduction implements StockDeductionStrategy {

    static final String KEY_PREFIX = "stock:";
    private static final long INSUFFICIENT = -1L;
    private static final long MISSING = -2L;

    private final StringRedisTemplate redis;
    private final StoresItemsRepository items;
    private final RedisScript<Long> deductScript;
    private final Counter success;
    private final Counter insufficient;
    private final Counter warmup;
    private final Counter compensated;
    private final Timer script;

    public RedisStockDeduction(StringRedisTemplate redis, StoresItemsRepository items, MeterRegistry registry) {
        this.redis = redis;
        this.items = items;
        this.deductScript = RedisScript.of(new ClassPathResource("redis/stock-deduct.lua"), Long.class);
        this.success = outcome(registry, "success");
        this.insufficient = outcome(registry, "insufficient");
        this.warmup = outcome(registry, "warmup");
        this.compensated = outcome(registry, "compensated");
        this.script = Timer.builder("stock.redis.script").register(registry);
    }

    @Override
    public void deduct(Long itemId, int quantity) {
        String key = key(itemId);
        long result = execute(key, quantity);
        if (result == MISSING) {
            warmup.increment();
            warmUp(itemId, key);
            result = execute(key, quantity);
            if (result == MISSING) {
                throw new IllegalStateException("재고 키를 적재하지 못했습니다: " + key);
            }
        }
        if (result == INSUFFICIENT) {
            insufficient.increment();
            throw new OrderException.InsufficientStock();
        }
        success.increment();
        onRollback(() -> {
            redis.opsForValue().increment(key, quantity);
            compensated.increment();
        });
    }

    @Override
    public void restore(Long itemId, int quantity) {
        afterCommit(() -> redis.opsForValue().increment(key(itemId), quantity));
    }

    @Override
    public void initialize(Long itemId, int quantity) {
        afterCommit(() -> redis.opsForValue().set(key(itemId), String.valueOf(quantity)));
    }

    private long execute(String key, int quantity) {
        Long result = script.record(() -> redis.execute(deductScript, List.of(key), String.valueOf(quantity)));
        if (result == null) {
            throw new IllegalStateException("재고 스크립트가 결과를 돌려주지 않았습니다: " + key);
        }
        return result;
    }

    private void warmUp(Long itemId, String key) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        redis.opsForValue().setIfAbsent(key, String.valueOf(item.getRemainingQuantity()));
    }

    private static void onRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    action.run();
                }
            }
        });
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String key(Long itemId) {
        return KEY_PREFIX + itemId;
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("stock.redis.deduct").tag("outcome", outcome).register(registry);
    }
}
```

### 3.1 읽는 순서

- `@Transactional` 을 붙이지 않는다. `create` 의 트랜잭션에 동기화만 등록한다. 트랜잭션 밖에서 단독 호출되면 (`isSynchronizationActive()` 가 false)
  `restore`·`initialize` 는 즉시 실행하고 `deduct` 는 보상을 등록하지 않는다. 단위 테스트가 이 경로를 탄다
- `StringRedisTemplate` 은 `spring-boot-starter-data-redis` 가 자동 구성한다 (`ieum-api/build.gradle` 에 이미 있음). 인자·키·값 전부 문자열이라 스크립트의 `ARGV[1]` 도 `"1"` 로 들어가고 `tonumber` 가 받는다
- `RedisScript.of(Resource, Class)` 는 `DefaultRedisScript` 를 만들고 SHA1 을 계산해 둔다. 애플리케이션 기동 시 한 번 읽는다
- `warmUp` 의 `findById` 는 `create` 가 이미 같은 엔티티를 영속성 컨텍스트에 올려 두었으므로 SQL 을 내지 않는다 (conditional 과 같은 사정)
- `onRollback` 의 `afterCompletion` 은 Spring 이 예외를 삼키고 로그만 남긴다 (`TransactionSynchronizationUtils`). 보상 INCRBY 가 실패해도 응답은 이미 정해진 롤백 응답이고, 실패한 만큼 재고가 샌다. 로그 한 줄이 Reconciliation 전까지의 유일한 흔적이므로 카운터를 INCRBY 성공 뒤에 둔다
- `afterCommit` 의 예외는 호출자에게 전파된다. `cancel` 에서 Redis 가 죽어 있으면 DB 는 CANCELED 로 커밋됐는데 500 이 나간다. 이것도 Reconciliation 이 잡을 창이며 함정 절에 적는다

### 3.2 `InsufficientStock` 에 숫자를 넣지 않는 이유

`-1` 은 "지금 `remaining < quantity`" 라는 사실만 돌려준다. 남은 수량을 응답에 넣으려면 스크립트가 `-1` 대신 `-(remaining + 1)` 같은
인코딩을 하거나 GET 을 한 번 더 해야 한다. conditional 과 같은 이유로 넣지 않는다 (2단계 조건부 가이드 2.2).

---

## 4. 인터페이스 — `initialize` 기본 메서드와 `StoreItemService`

상품 등록 직후 키를 채우는 경로가 필요하다. `StoreItemService` 가 `RedisStockDeduction` 을 직접 알면 전략 교체가 깨지므로
인터페이스에 기본 메서드를 둔다.

```java
public interface StockDeductionStrategy {

    void deduct(Long itemId, int quantity);

    void restore(Long itemId, int quantity);

    default void initialize(Long itemId, int quantity) {
    }
}
```

`naive`·`optimistic`·`conditional` 은 DB 가 원장이라 할 일이 없고, `redis` 만 재정의한다. `StoreItemService.create` 의 TODO 자리를 바꾼다.

```java
StoresItems saved = items.save(item);
stock.initialize(saved.getId(), saved.getInitialQuantity());
return ItemResponse.from(saved);
```

`StoreItemService` 생성자에 `StockDeductionStrategy stock` 을 추가한다. `initialize` 는 `afterCommit` 에 등록되므로 커밋 전에는
키가 없고, 그 사이 들어온 예약은 워밍업 경로(`-2` → `SET NX`) 로 같은 값을 넣는다. 둘 다 `initialQuantity` 라 어느 쪽이 먼저든 같다.
단, `initialize` 의 `SET` 은 `NX` 가 아니다. 워밍업이 먼저 넣고 첫 예약이 차감한 뒤 `SET` 이 덮어쓰면 차감이 사라진다. 이 순서는
"커밋 전에 그 상품으로 예약이 들어온다" 는 뜻인데, `create` 가 `findByUid` 로 그 상품을 찾으려면 커밋이 끝나 있어야 하므로 실제로는 생기지 않는다.
그래도 `setIfAbsent` 로 바꾸면 논리가 단순해지니 취향대로. 이 문서의 코드는 `set` 이다 (재고 조정 API 가 생기면 그때는 덮어쓰는 `set` 이 맞다).

---

## 5. 설정과 주석

- `application.yaml` — 바꿀 것 없음. `ieum.stock.strategy` 주석에 `redis` 는 이미 있다
- `.env.example` — `STOCK_STRATEGY` 설명의 `redis(Lua)` 를 `redis(Lua 원자 차감, DB 열은 투영)` 정도로. Redis 접속값은 이미 필수 항목
- `StockDeductionStrategy` 주석 — `initialize` 가 왜 기본 메서드인지 한 줄
- `scripts/k6/create-order.js` 상단 주석 — "DB 불변식은 reset-loadtest.sql 상단 주석의 쿼리로 확인" 뒤에 "`redis` 는 `remaining_quantity` 대신 `GET stock:{id}` 를 더한다" 한 줄
- `scripts/sql/seed-loadtest.sql`·`reset-loadtest.sql` 의 `TODO(3단계 Redis)` 두 줄 — SQL 로는 못 하므로 주석을 실제 명령으로 바꾼다

  ```text
  -- redis 전략일 때는 이 스크립트 뒤에 재고 키도 되돌린다 (item id 는 SELECT id FROM stores_items WHERE uid = @item_uid):
  --   docker exec ieum-redis redis-cli -a "<REDIS_PASSWORD>" --no-auth-warning SET stock:<item_id> 100
  ```

  시드를 다시 돌리면 상품 행이 지워지고 새 id 로 들어가므로 id 는 매번 확인한다. 옛 id 의 키는 `DEL` 한다
- `OrderProperties`·`OrderCreateRetrier`·`ApiExceptionAdvice` 는 손대지 않는다. `conflict`·`exhausted`·`deadlock` 이 0 이고 `attempts.used` 가 전부 `le="1"` 인 것이 conditional 과 같은 증거가 된다
- `RedisStockDeduction` 상단의 설계 주석은 지운다. 결정 사항은 이 문서와 ADR 에 있고, 코드에는 주석을 두지 않는다

---

## 6. 테스트

### 6.1 단위 — `RedisStockDeductionTest` (Docker 없음)

`ieum-api/src/test/java/com/hwannee/ieum/orders/stock/RedisStockDeductionTest.java`. `ConditionalUpdateStockDeductionTest` 와 같은 순수 Mockito.
`StringRedisTemplate` 과 `ValueOperations<String, String>` 을 `@Mock` 으로 두고 `given(redis.opsForValue()).willReturn(ops)`.
`MeterRegistry` 는 `new SimpleMeterRegistry()` 를 직접 넘긴다 (`@InjectMocks` 대신 `@BeforeEach` 에서 생성자 호출).

`redis.execute(RedisScript<T>, List<K>, Object...)` 는 가변 인자라 스텁은 `given(redis.execute(any(), eq(List.of("stock:10")), eq("1"))).willReturn(5L)` 형태.
`any()` 의 제네릭 경고는 `@SuppressWarnings("unchecked")` 로 닫는다.

| 케이스 | given | then |
|---|---|---|
| 재고가 있으면 스크립트 한 번으로 끝난다 | `execute` → 5 | 예외 없음. `execute` 1회. `ops.setIfAbsent` `never()`. `success` 카운터 1 |
| 부족하면 `InsufficientStock` (메시지에 숫자 없음) | `execute` → -1 | `InsufficientStock`, `hasMessage("재고가 부족합니다.")`. `insufficient` 1 |
| 키가 없으면 DB 값으로 `SET NX` 후 재실행 | `execute` → -2 then 9, `items.findById` → remaining 10 | `ops.setIfAbsent("stock:10", "10")` 1회, `execute` 2회, `warmup` 1 |
| 워밍업 후에도 키가 없으면 `IllegalStateException` | `execute` → -2, -2 | 예외 |
| 트랜잭션이 롤백되면 보상 INCRBY | `TransactionSynchronizationManager.initSynchronization()` 후 `deduct`, 등록된 동기화의 `afterCompletion(STATUS_ROLLED_BACK)` 직접 호출 | `ops.increment("stock:10", 1L)` 1회, `compensated` 1. `finally` 에서 `clearSynchronization()` |
| 커밋되면 보상하지 않는다 | 같은 준비, `afterCompletion(STATUS_COMMITTED)` | `ops.increment` `never()` |
| 부족으로 끝나면 롤백돼도 보상하지 않는다 | `initSynchronization()`, `execute` → -1 | `getSynchronizations()` 가 비어 있음 |
| 트랜잭션 밖 `restore` 는 즉시 INCRBY | 동기화 없이 `restore(10L, 2)` | `ops.increment("stock:10", 2L)` |
| 트랜잭션 안 `restore` 는 커밋 후 INCRBY | `initSynchronization()`, `restore`, 등록된 동기화의 `afterCommit()` 호출 | 호출 전 `never()`, 호출 후 1회 |
| `initialize` 는 SET | `initialize(10L, 100)` | `ops.set("stock:10", "100")` |

### 6.2 통합 — `RedisStockDeductionIT` (Docker Redis 가 있을 때만)

스크립트 자체의 원자성은 모킹으로 증명되지 않는다. 진짜 Redis 에 100 스레드가 동시에 차감해 정확히 재고만큼만 성공하는지 본다.
`contextLoads` 처럼 MySQL 까지 띄우지 않도록 `@SpringBootTest` 를 쓰지 않고 Lettuce 팩터리를 직접 만든다.

```java
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true")
class RedisStockDeductionIT {

    static LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    RedisStockDeduction strategy;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        conf.setPassword(System.getenv("REDIS_PASSWORD"));
        factory = new LettuceConnectionFactory(conf);
        factory.afterPropertiesSet();
        factory.start();
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis = new StringRedisTemplate(factory);
        strategy = new RedisStockDeduction(redis, Mockito.mock(StoresItemsRepository.class), new SimpleMeterRegistry());
        redis.opsForValue().set("stock:999", "10");
    }

    @Test
    void 동시에_100건이_차감해도_정확히_10건만_성공한다() throws Exception {
        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger sold = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    strategy.deduct(999L, 1);
                    ok.incrementAndGet();
                } catch (OrderException.InsufficientStock e) {
                    sold.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(10);
        assertThat(sold.get()).isEqualTo(90);
        assertThat(redis.opsForValue().get("stock:999")).isEqualTo("0");
    }
}
```

실행 (Git Bash, `.env` 의 값으로):

```bash
REDIS_IT=true REDIS_PASSWORD=<REDIS_PASSWORD> ./gradlew :ieum-api:test --tests "*RedisStockDeductionIT"
```

평소의 `./gradlew :ieum-api:test --tests "com.hwannee.ieum.orders.*"` 에서는 환경변수가 없어 건너뛴다. 키 `stock:999` 는 시드 상품과 겹치지 않는 값이고, 테스트 뒤 `DEL` 로 지운다 (`@AfterEach`).

`OrderServiceTest`·`OrderCreateRetrierTest` 는 전략을 모킹하므로 바뀌지 않는다. `StoreItemService` 에 생성자 인자가 늘어나므로 그 서비스의 테스트가 있으면 `@Mock StockDeductionStrategy` 를 더한다 (지금은 없음).

---

## 7. 측정 절차

2단계 조건부 가이드 5절과 같다. 다른 점만 적는다.

1. `.env` 에 `STOCK_STRATEGY=redis`, `SQL_LOG_LEVEL=warn`, `SQL_BIND_LOG_LEVEL=off`. 재시도 설정은 기본값 그대로
2. `reset-loadtest.sql` 로 DB 를 되돌린 뒤 **Redis 키를 넣는다**

   ```bash
   docker exec -i ieum-mysql mysql -u root -p"<MYSQL_ROOT_PASSWORD>" <MYSQL_DATABASE> \
     -e "SELECT id FROM stores_items WHERE uid = '33333333-3333-3333-3333-333333333333'"
   docker exec ieum-redis redis-cli -a "<REDIS_PASSWORD>" --no-auth-warning SET stock:<item_id> 100
   docker exec ieum-redis redis-cli -a "<REDIS_PASSWORD>" --no-auth-warning CONFIG RESETSTAT
   ```

   `CONFIG RESETSTAT` 은 `INFO commandstats` 를 0 으로 만들어 이 라운드의 `evalsha` 호출 수와 평균 시간을 그대로 읽게 한다
3. 두 서버 기동. 예약 1건을 `SQL_LOG_LEVEL=debug` 로 먼저 보내 **`update stores_items` 가 전혀 찍히지 않고** `insert into users_orders` 만 나가는지,
   `GET stock:<item_id>` 가 99 인지 확인한 뒤 `warn` 으로 되돌리고 2번을 다시 (`reset-loadtest.sql` + `SET` + `RESETSTAT`), API 서버 재기동으로 카운터 0
4. 행 락 통계 스냅샷 (k6 직전·직후) — 조건부 가이드 5절 4번과 같다. 이번에는 증가분이 0 이어야 한다
5. 스크랩 루프 (2단계 가이드 4.4, 파일명은 `stage3-` 로, grep 패턴에 `stock_redis_` 추가) → `k6 run scripts/k6/create-order.js` → 루프 정지 → 최종 스냅샷
6. Redis 확인

   ```bash
   docker exec ieum-redis redis-cli -a "<REDIS_PASSWORD>" --no-auth-warning GET stock:<item_id>
   docker exec ieum-redis redis-cli -a "<REDIS_PASSWORD>" --no-auth-warning INFO commandstats | grep -E "evalsha|eval|incrby|set"
   curl -s localhost:8080/actuator/prometheus | grep -E '^stock_redis_'
   ```

   `cmdstat_evalsha` 의 `calls` 가 10,000 (+ `eval` 1, 첫 호출의 `NOSCRIPT` 폴백), `usec_per_call` 이 이 라운드의 Redis 비용. `cmdstat_incrby` 는 0 이어야 한다 (보상 0)
7. DB 확인 쿼리 — 조건부 가이드의 넷 중 `remaining_quantity` 는 100 그대로인 것을 확인하고, 불변식은 아래로 대신한다

   ```sql
   SELECT order_state, COUNT(*) AS cnt, SUM(quantity) AS qty FROM users_orders o JOIN stores_items i ON i.id = o.store_item_id
    WHERE i.uid = '33333333-3333-3333-3333-333333333333' GROUP BY order_state;
   ```

   `initial_quantity (100) = GET stock:<item_id> + SUM(qty of PENDING)` 이면 성립. `version` 0, `MAX(id) − COUNT(*)` 0, `LATEST DETECTED DEADLOCK` 없음은 같다
8. `reset-loadtest.sql` + `SET stock:<item_id> 100`

### 7.1 기록할 것

`performance/<날짜>-stage3-redis.md`. 조건부 문서의 "결과 요약" 표에 이 라운드 열을 넣고, 다섯 측정 (naive / optimistic 1·2 / conditional / redis) 비교표를 둔다. 추가 행:

| 항목 | 출처 |
|---|---|
| `stock.redis.deduct{success / insufficient / warmup / compensated}` = 100 / 9,900 / 0 / 0 | Prometheus |
| `stock_redis_script_seconds` count / 평균 / max | Prometheus (Lettuce 왕복 포함한 애플리케이션 쪽 시간) |
| `cmdstat_evalsha` calls / usec_per_call | `INFO commandstats` (Redis 쪽 시간. 둘의 차이가 네트워크·풀 대기) |
| Redis `stock:{id}` 최종값, DB `remaining_quantity` 최종값 (0 / 100) | 6·7번 |
| 불변식 `initial = Redis stock + active` | 계산 |
| `Innodb_row_lock_waits` 증가분 (0 이어야 함) | 4번 |
| 재고 소진까지 (`created_at` MIN → MAX) | DB |

### 7.2 해석에 넣을 것

- **행 락 대기가 0 인가.** 0 이면 "재고 행 직렬화" 가 사라진 것이 확인된다. INSERT 의 FK 검사는 `stores_items` 에 S 락을 잡지만 S 끼리는 충돌하지 않는다.
  0 이 아니면 어딘가 `stores_items` 를 UPDATE 하는 경로가 남아 있다 (`version` 이 0 이 아닌지 같이 본다)
- **재고 소진 시간.** conditional 의 0.76초는 성공 100 건이 7.6ms 간격으로 한 줄로 커밋한 값이었다. Redis 에서는 차감이 µs 단위로 끝나므로 소진 시간은 "100 VU 가 첫 100 요청을 보내는 데 걸린 시간" 에 수렴한다.
  이 값이 곧 "한 상품 성공 처리 상한 ≈ 130 건/s" 가 풀린 증거
- **p99·처리량.** 성공 100 건 뒤의 9,900 건은 SELECT 3 → Lua `-1` → 409 로, DB 왕복 수가 conditional 의 409 경로와 같다. 그래서 p99 는 naive 633 / conditional 669ms 근처에 머물 가능성이 크다.
  그렇다면 결론은 "이 부하의 p99 는 VU 100 / 풀 20 의 커넥션 대기이고, 재고 전략은 4 라운드에 걸쳐 그 위에서 정합성·소진 실패·직렬화를 차례로 걷어냈다" 이다.
  p99 가 뚜렷이 내려가면 conditional 의 행 락 대기(평균 117ms) 가 `acquire` 에 가려져 있었던 것이고 그 몫을 적는다
- **`acquire` 평균.** 트랜잭션에서 X 락 대기가 빠지므로 커넥션 보유 시간이 줄고 `acquire` 평균 95ms 는 내려갈 여지가 있다. `pending` 최대 80 은 그대로일 것
- **보상 복구 0.** 부하 중 Redis↔DB 창이 열리지 않았다는 뜻이지, 창이 없다는 뜻이 아니다. 1.4 의 두 창(차감 후 커밋 전 크래시, 커밋 후 INCRBY 전 크래시) 을 그대로 적고 Reconciliation 으로 넘긴다
- **무엇이 남는가.** (1) 중복 활성 예약 검사가 여전히 DB 조회 → README V1 대로 Lua 안으로 (todo 2.2). (2) 요청당 SELECT 3 이 커넥션을 점유 → 풀 대기의 정체. 사전 검사를 Redis 로 옮기거나 캐시하면 DB 는 INSERT 만 남는다.
  (3) DB `remaining_quantity` 가 투영이 됨 → 조회 API 와 Reconciliation. (4) 복구 상한 검사 없음 → Reconciliation. (5) Redis 가 단일 장애점 → AOF·noeviction 은 되어 있고, 재기동 시 워밍업 경로가 DB 투영에서 채우는데 투영이 stale 이면 그 값도 stale 이다.
  Reconciliation 이 DB 투영을 최신으로 유지해야 워밍업이 안전하다. 이것이 Reconciliation 을 "선택" 이 아니라 "필수" 로 만드는 이유

### 7.3 ADR-0003

- 결과 표 `3. redis` 행을 채운다. 재시도 열은 "없음", 기록 열에 행 락 대기 0 · 보상 0 · 소진 시간
- 상태를 "결정" 으로 바꾸고 결정 절을 확정한다. 넷을 나란히 놓고 무엇을 채택하는지, 어떤 조건에서 `conditional` 이 더 나은 답인지 (Redis 를 추가 운영 부담 없이 쓸 수 없는 환경, 상품당 처리 상한 130 건/s 로 충분한 트래픽) 한 단락
- "Redis↔DB 정합성" 절을 새로 두고 1.4 의 창 두 개와 Reconciliation 의 역할을 적는다. 이 절이 todo 2.2 나머지 항목(Expiry Worker, 복구 멱등성, Reconciliation) 의 배경이 된다

### 7.4 todo

- 2.1 동시성 3번에 완료 날짜, 2.2 "Redis Lua Script 기반 원자적 재고 차감" 체크 + 결과 한 줄 + performance 링크
- 2.2 에 새 항목: "`redis` 전략에서 `GET /api/items/{itemUid}` 의 `remainingQuantity` 는 stale — 조회 경로 결정", "Reconciliation 이 DB 투영을 갱신해야 워밍업이 안전 (필수)"
- 2.2 "재고 복구 멱등성" 에 "`restore` 시그니처에 `orderId` 추가는 Expiry Worker 와 함께" 메모

---

## 8. 함정

- **`deduct` 에 `@Transactional` 을 붙이지 않는다.** 붙여도 REQUIRED 라 `create` 에 참여할 뿐이지만, 트랜잭션 밖 호출에서 새 DB 트랜잭션이 열려 커넥션을 헛되이 잡는다. Redis 전략은 DB 트랜잭션이 필요 없다
- **보상은 `afterCompletion` 이지 `afterCommit` 의 반대가 아니다.** `afterCommit` 은 커밋 성공 시에만, `afterCompletion(int)` 은 커밋·롤백·불명 모두에서 호출된다. 상태 비교를 `STATUS_ROLLED_BACK` 으로 정확히 하고 `STATUS_UNKNOWN` 은 건너뛴다
- **`afterCompletion` 의 예외는 삼켜진다.** Spring 이 `TransactionSynchronizationUtils` 에서 로그로 바꾼다. 보상 실패는 로그 외에 흔적이 없으므로 카운터는 INCRBY 성공 뒤에 올리고, 실패는 로그 레벨을 확인해 둔다 (기본 `ERROR` 로 찍힌다)
- **`afterCommit` 의 예외는 전파된다.** `cancel` 이 커밋된 뒤 Redis 장애로 INCRBY 가 실패하면 클라이언트는 500 을 받지만 DB 는 CANCELED 다. 클라이언트가 재시도하면 `InvalidOrderStateException` 으로 끝나고 재고는 안 돌아온다. 이 창은 코드로 못 닫고 Reconciliation 이 잡는다
- **`InsufficientStock` 뒤에 보상을 등록하면 안 된다.** 차감이 없었는데 롤백 시 INCRBY 하면 재고가 는다. 코드 순서(예외 throw 뒤에 `onRollback`) 가 이것을 보장하고, 단위 테스트 "부족으로 끝나면 보상하지 않는다" 가 고정한다
- **워밍업의 `SET` 은 반드시 `NX`.** 100 VU 가 동시에 `-2` 를 받고 각자 DB 값으로 `SET` 하면 먼저 차감한 요청의 결과를 뒤의 `SET` 이 덮어쓴다
- **DB `remaining_quantity` 로 `create` 의 사전 검사를 하지 않는다.** conditional 은 스냅샷으로 재고 0 을 먼저 걸렀지만 redis 에서 DB 열은 stale 이다. 재고 판정은 Lua 결과 하나만 본다
- **키는 `KEYS` 로, 수량은 `ARGV` 로.** 스크립트 본문에 키를 문자열로 박으면 Cluster 에서 `CROSSSLOT`·라우팅 문제가 생긴다. 지금은 단일 노드라 동작하지만 습관을 잘못 들이면 V3 에서 고친다
- **`tonumber(ARGV[1])` 를 빼먹으면** Lua 가 문자열 `"1"` 과 숫자를 비교하다 오류를 낸다. `redis.call('DECRBY', key, ARGV[1])` 은 문자열 그대로 넘겨도 Redis 가 파싱한다
- **`StringRedisTemplate` 에 `Long` 을 인자로 넘기지 않는다.** 직렬화기가 `StringRedisSerializer` 라 `String.valueOf(quantity)` 로 넘긴다. 다른 타입은 `SerializationException`
- **Spring Boot 4 패키지.** `spring-boot-starter-data-redis` 의 자동 구성 패키지는 `org.springframework.boot.data.redis.autoconfigure` 로 옮겨졌지만 `StringRedisTemplate`·`RedisScript`·`LettuceConnectionFactory` 는 Spring Data Redis 것이라 그대로다. `@DataRedisTest` 는 쓰지 않는다 (6.2 처럼 직접 만든다)
- **`LettuceConnectionFactory` 를 직접 만들면 `afterPropertiesSet()` 뒤에 `start()`.** Spring Data Redis 3 부터 `SmartLifecycle` 이라 컨테이너 밖에서는 명시적으로 시작해야 연결한다. 안 하면 첫 명령에서 "LettuceConnectionFactory was not initialized"
- **라운드 사이에 `stock:{id}` 를 되돌리는 것을 빼먹으면** 두 번째 라운드가 재고 0 에서 시작해 201 이 0 이 된다. `reset-loadtest.sql` 상단 주석의 명령을 SQL 직후에 실행하는 습관
- **시드를 다시 돌리면 item id 가 바뀐다.** 옛 키 `stock:<old_id>` 는 `DEL`, 새 id 로 `SET`. `KEYS stock:*` 로 남은 것을 보되 운영 Redis 에서는 `SCAN` (todo 1.4 의 `rename-command KEYS`)
- **`compensated` 가 0 이 아니면** 부하 중 INSERT 나 커밋이 실패한 것이다. API 로그의 예외 타입을 먼저 본다. `DataIntegrityViolation` 이면 시드 문제, `CannotCreateTransaction` 이면 풀·DB 문제
- **`contention 503` 체크를 지우지 않는다.** conditional 과 같은 이유로 0 이 찍혀야 재시도 계층이 새지 않았다는 것을 k6 출력만으로 읽는다

---

## 9. 커밋 단위

파일당 하나씩.

1. `feat: add stock-deduct.lua for atomic check-and-decrement`
2. `feat: add initialize default method to StockDeductionStrategy`
3. `feat: implement RedisStockDeduction with rollback compensation and after-commit restore`
4. `feat: initialize redis stock key after item creation in StoreItemService`
5. `docs: describe redis strategy in .env.example` / k6 주석 / `seed-loadtest.sql` 주석 / `reset-loadtest.sql` 주석 (각각 별도)
6. `test: add RedisStockDeductionTest`
7. `test: add RedisStockDeductionIT gated by REDIS_IT`
8. `docs: record redis lua load test results` / ADR-0003 / todo (각각 별도)
