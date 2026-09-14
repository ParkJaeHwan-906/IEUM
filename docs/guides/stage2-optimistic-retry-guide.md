# 2단계 낙관적 락 + 재시도 계층 구현 가이드

todo 2.2 의 "2단계 낙관적 락 + 재시도 계층" 1~6번을 순서대로 구현하고 측정하는 절차입니다.
0번(로그 끈 기준선 재측정)은 2026-09-14 에 끝났고, 비교 기준은
[performance/2026-09-12-stage1-naive.md](../performance/2026-09-12-stage1-naive.md) 의 재측정 절입니다.

> 코드 조각은 현재 저장소(Spring Boot 4.1.1 / Java 21 / Hibernate 7.4) 의 실제 클래스 이름과 시그니처에
> 맞춰 썼지만, 컴파일해 본 것은 아닙니다. 마지막 "함정" 절을 먼저 읽어 두면 시간을 아낍니다.

---

## 0. 지금 코드에서 이미 정해진 것

- `StoresItems` 에 `@Version Long version` 이 있고, `OptimisticLockStockDeduction.deduct` 는 엔티티를 읽어
  `decreaseQuantity` 만 호출한다. UPDATE 는 감싸는 트랜잭션의 커밋 시점에 더티 체킹으로 나간다
- `OrderService.create` 가 `@Transactional` 이고, 그 안에서 `stock.deduct` → `orders.save` 순서로 실행된다.
  `UsersOrders` 가 IDENTITY 전략이라 `save` 시점에 INSERT 가 즉시 나가고, 재고 UPDATE 는 커밋 직전 flush 에서 나간다
- 충돌은 `UPDATE ... WHERE id = ? AND version = ?` 가 0건을 갱신할 때 Hibernate 가 `StaleObjectStateException`
  을 던지고, `JpaTransactionManager` 가 커밋 시점에 이를 `ObjectOptimisticLockingFailureException` 으로 번역해
  **프록시 바깥으로** 던진다. 즉 `create` 메서드 본문 안에서는 이 예외를 볼 수 없다
- `ApiExceptionAdvice` 가 이 예외를 잡아 409 로 바꾸고 있다. 재시도 계층이 생기면 여기 도달하는 것은 소진된 요청뿐이어야 한다

한 번의 충돌 시도가 DB 에 남기는 비용은 `SELECT stores_items` + `INSERT users_orders` + 실패한 `UPDATE` + 롤백이다.
INSERT 가 롤백되어도 auto-increment 는 소비되므로, 측정 후 `users_orders` 의 `MAX(id)` 와 건수 차이가
"실패 시도의 DB 비용" 을 보여 주는 부수 증거가 된다.

---

## 1. 재시도 계층 — `OrderCreateRetrier`

### 1.1 왜 별도 빈인가

같은 빈 안에서 `create` 를 catch 해 다시 호출하면 `this.create(...)` 는 프록시를 거치지 않는다.
이미 rollback-only 로 표시된 트랜잭션 안에서 다시 실행되거나, 아예 트랜잭션 없이 실행된다.
재시도는 **프록시 바깥에서, 매번 새 트랜잭션으로** 해야 하므로 `OrderService` 를 주입받는 별도 빈이 필요하다.
이 빈에는 `@Transactional` 을 붙이지 않는다.

spring-retry 대신 수동 루프를 쓰는 이유는 todo 에 적힌 대로 AOP 한 겹이 더 생겨 계측이 흐려지기 때문이다.
루프는 10줄이면 끝난다.

### 1.2 위치와 시그니처

`ieum-api/src/main/java/com/hwannee/ieum/orders/service/OrderCreateRetrier.java`

```java
package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

@Component
public class OrderCreateRetrier {

    private final OrderService orderService;
    private final OrderProperties.Retry retry;
    private final Counter success;
    private final Counter conflict;
    private final Counter exhausted;
    private final DistributionSummary attemptsUsed;

    public OrderCreateRetrier(OrderService orderService, OrderProperties properties, MeterRegistry registry) {
        this.orderService = orderService;
        this.retry = properties.retry();
        this.success = outcome(registry, "success");
        this.conflict = outcome(registry, "conflict");
        this.exhausted = outcome(registry, "exhausted");
        this.attemptsUsed = DistributionSummary.builder("order.create.attempts.used")
                .serviceLevelObjectives(IntStream.rangeClosed(1, retry.maxAttempts()).asDoubleStream().toArray())
                .register(registry);
    }

    public OrderResponse create(AuthenticatedUser user, CreateOrderRequest request, String idempotencyKey) {
        for (int attempt = 1; ; attempt++) {
            try {
                OrderResponse response = orderService.create(user, request, idempotencyKey);
                success.increment();
                attemptsUsed.record(attempt);
                return response;
            } catch (OptimisticLockingFailureException e) {
                conflict.increment();
                if (attempt >= retry.maxAttempts()) {
                    exhausted.increment();
                    throw e;
                }
                backoff(attempt);
            }
        }
    }

    private void backoff(int attempt) {
        long upperBound = retry.backoff().toNanos() * attempt;
        if (upperBound <= 0) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(ThreadLocalRandom.current().nextLong(upperBound + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("order.create.attempts").tag("outcome", outcome).register(registry);
    }
}
```

설계 포인트를 코드 밖에 적어 둡니다.

- **catch 대상은 `OptimisticLockingFailureException`** (부모). 커밋 시점 번역 결과는 `ObjectOptimisticLockingFailureException`
  또는 그 하위인 `JpaOptimisticLockingFailureException` 인데, 둘 다 이 부모 아래에 있다. 부모를 잡아야 경로에 따라
  타입이 달라져도 새지 않는다
- **`ApiException` 은 잡지 않는다.** `InsufficientStock`·`DuplicateActiveOrder`·`ItemNotOnSale` 은 확정된 답이므로
  그대로 위로 올라가 `ApiExceptionAdvice` 가 처리한다. 재시도 루프의 catch 절에 `RuntimeException` 을 쓰면 안 된다
- **재시도마다 새 트랜잭션이 재고를 다시 읽는다.** 이전 시도가 stale 값(예: remaining 1)을 읽고 충돌했더라도, 다음 시도에서
  0 을 읽으면 `InsufficientStock` 으로 끝난다. 재고가 없는데 상한까지 반복하는 일은 없다
- **첫 시도가 롤백되면 주문도 저장되지 않는다.** 그래서 두 번째 시도의 `DuplicateActiveOrder` 검사는 오탐하지 않는다
- **백오프에 지터를 넣는다.** VU 100 이 같은 고정 지연으로 재시도하면 다음 시도에서 다시 같은 순간에 부딪힌다.
  `[0, backoff × attempt]` 균등 난수(full jitter)가 가장 단순하다. 이 결정은 ADR-0003 에 한 줄 남긴다
- **`Thread.sleep` 은 Tomcat 요청 스레드를 붙잡는다.** 이것 자체가 낙관적 락의 비용 중 하나이므로 숨기지 않는다.
  Tomcat 기본 최대 스레드 200 > VU 100 이라 측정에는 지장이 없다
- 카운터 세 개는 생성자에서 한 번 등록한다. `MeterRegistry` 는 같은 이름·태그로 다시 `register` 해도 같은 인스턴스를
  돌려주지만, 요청마다 빌더를 타는 것은 불필요하다
- `order.create.attempts.used` 는 "성공까지 걸린 시도 횟수" 분포다. SLO 버킷을 1..maxAttempts 로 두면 Prometheus 에서
  `_bucket{le="1"}` (한 번에 성공), `le="2"`, `le="3"` 로 바로 읽힌다

### 1.3 컨트롤러 교체

`OrderController` 의 생성자 주입을 `OrderService` → `OrderCreateRetrier` 로 바꾸고, `create` 만 새 빈을 호출한다.
`mine`·`cancel` 은 `OrderService` 를 그대로 쓴다. 두 빈을 모두 주입받는 형태가 된다.

`naive`·`redis` 전략에서는 충돌이 없어 첫 시도에 통과하므로, 전략과 무관하게 같은 경로를 탄다.
따라서 `@ConditionalOnProperty` 로 재시도 빈을 전략에 묶지 않는다.

---

## 2. 설정값

### 2.1 `OrderProperties`

```java
@ConfigurationProperties(prefix = "ieum.order")
public record OrderProperties(
        @DefaultValue("PT15M") Duration pickupTtl,
        @DefaultValue Retry retry
) {
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("PT0.01S") Duration backoff
    ) {
    }
}
```

중첩 레코드 앞의 빈 `@DefaultValue` 가 있어야 `ieum.order.retry.*` 를 하나도 지정하지 않았을 때 `retry` 가 `null` 이
아니라 기본값으로 채워진 인스턴스가 된다.

### 2.2 `application.yaml` (ieum-api)

```yaml
ieum:
  order:
    pickup-ttl: ${PICKUP_TTL:PT15M}
    retry:
      max-attempts: ${STOCK_RETRY_MAX_ATTEMPTS:3}
      backoff: ${STOCK_RETRY_BACKOFF:PT0.01S}
```

### 2.3 `.env.example`

`STOCK_STRATEGY` 아래에 이어서:

```text
# optimistic 전략에서 @Version 충돌 시 재시도 상한과 기본 백오프. 실제 대기는 [0, backoff × 시도 횟수] 균등 난수
#STOCK_RETRY_MAX_ATTEMPTS=3
#STOCK_RETRY_BACKOFF=PT0.01S
```

---

## 3. 최종 실패 응답 — 503 + `Retry-After` 를 권장

현재 409 "요청이 몰려 처리하지 못했습니다" 를 **503 Service Unavailable + `Retry-After: 1`** 로 바꾸는 것을 권장합니다.

- 이 API 에서 409 는 이미 "확정된 거절" 의미로 쓰고 있다 (`InsufficientStock`, `DuplicateActiveOrder`, `ItemNotOnSale`).
  클라이언트는 409 를 받으면 같은 요청을 다시 보내지 않는다. 재시도 소진은 반대로 "서버가 답을 못 정했으니
  다시 보내라" 이므로 다른 코드여야 한다
- 측정 관점에서도 갈라야 한다. k6 체크에 `contention 503` 을 추가하면 **"재고가 있는데 답을 못 준 소진 실패"** 건수가
  409(재고 소진 이후의 정상 거절)와 섞이지 않고 바로 읽힌다. 이것이 ADR-0003 에 기록할 2단계의 핵심 수치다

`ApiExceptionAdvice` 의 핸들러를 다음처럼 바꾼다. `Retry-After` 헤더를 붙이려면 `ProblemDetail` 대신
`ResponseEntity<ProblemDetail>` 을 돌려준다.

```java
@ExceptionHandler(OptimisticLockingFailureException.class)
public ResponseEntity<ProblemDetail> optimisticLockExhausted(OptimisticLockingFailureException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem);
}
```

여기서도 부모 타입을 잡는다. `cancel` 의 `stock.restore` 도 `StoresItems` 를 갱신하므로 같은 충돌이 날 수 있는데,
그 경로에는 재시도 계층이 없다. 지금은 그대로 503 으로 두고, 취소 경로 재시도는 todo 에 한 줄 남긴다.

k6 스크립트 `create-order.js` 의 `check` 에 한 줄 추가:

```js
'contention 503': (r) => r.status === 503,
```

세 체크의 합이 10,000 이어야 하고, 모자라면 그만큼이 401/500 이다. 상단 주석의 "결과 읽는 법" 도 같이 고친다.

---

## 4. 계측

### 4.1 의존성

`spring-boot-starter-actuator` 가 이미 `spring-boot-starter-micrometer-metrics` 를 끌고 오고, 그 안에
`PrometheusMetricsExportAutoConfiguration` 과 `PrometheusScrapeEndpoint` 가 들어 있다. 레지스트리 구현만 추가한다.
버전은 Boot BOM 이 관리한다 (Micrometer 1.17).

```groovy
implementation 'io.micrometer:micrometer-registry-prometheus'
```

HikariCP 지표는 `spring-boot-jdbc` 의 `DataSourcePoolMetricsAutoConfiguration` 이 `MeterRegistry` 가 있으면
자동으로 바인딩한다. 따로 할 일이 없다.

### 4.2 노출

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

`SecurityConfig` 의 permitAll 줄에 `/actuator/prometheus` 를 더한다:

```java
.requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/prometheus").permitAll()
```

운영에서는 `management.server.port` 로 포트를 분리하고 네트워크 정책으로 막는 것이 맞지만, 분리해도 같은
`SecurityFilterChain` 이 적용되므로 permitAll 줄은 어차피 필요하다. 지금은 로컬 측정용으로 열고 todo 4절에
"운영 시 관리 포트 분리" 를 한 줄 남긴다.

### 4.3 지표 이름 (Prometheus 표기)

| Micrometer | Prometheus | 의미 |
|---|---|---|
| `order.create.attempts{outcome=success}` | `order_create_attempts_total{outcome="success"}` | 최종 성공 요청 수 (= 201 건수) |
| `order.create.attempts{outcome=conflict}` | `..._total{outcome="conflict"}` | 충돌한 시도 수 (요청당 여러 번) |
| `order.create.attempts{outcome=exhausted}` | `..._total{outcome="exhausted"}` | 상한 소진 요청 수 (= 503 건수) |
| `order.create.attempts.used` | `order_create_attempts_used_bucket{le="1|2|3"}` | 성공까지 걸린 시도 횟수 분포 |
| `hikaricp.connections.pending` | `hikaricp_connections_pending{pool="IeumHikariPool"}` | 커넥션 대기 스레드 수 (게이지) |
| `hikaricp.connections.acquire` | `hikaricp_connections_acquire_seconds_{count,sum,max}` | 커넥션 획득 시간 (누적) |

`pending` 은 **게이지** 라 측정이 끝난 뒤 스냅샷을 찍으면 0 이다. 실행 중에 주기적으로 긁어야 최댓값이 나온다.
나머지는 누적값이라 측정 직후 한 번 긁으면 된다.

### 4.4 스크랩 방법 — 실행 중 `curl` 루프

Prometheus 컨테이너 없이 Git Bash 창 하나로 충분하다. k6 를 시작하기 직전에 켜고, 끝나면 Ctrl+C.

```bash
cd /d/dev/IEUM/IEUM_BE
out=scripts/k6/out/stage2-$(date +%Y%m%d-%H%M%S).prom
mkdir -p scripts/k6/out
while true; do
  curl -s localhost:8080/actuator/prometheus \
    | grep -E '^(hikaricp_connections_(pending|active|acquire_seconds_max)|order_create_attempts_total)' \
    | sed "s/^/$(date +%T.%N) /" >> "$out"
  sleep 0.5
done
```

측정 후 읽기:

```bash
grep pending "$out" | awk '{print $NF}' | sort -n | tail -1      # pending 최댓값
grep acquire_seconds_max "$out" | awk '{print $NF}' | sort -n | tail -1
curl -s localhost:8080/actuator/prometheus | grep -E '^order_create_attempts'   # 최종 누적값
```

`scripts/k6/out/` 은 `.gitignore` 에 넣는다. 기록은 performance 문서에 숫자로 옮긴다.

---

## 5. 테스트 — `OrderCreateRetrierTest`

`ieum-api/src/test/java/com/hwannee/ieum/orders/service/OrderCreateRetrierTest.java`.
스프링 컨텍스트 없이 Mockito + `SimpleMeterRegistry` 로 끝낸다. `OrderService` 는 final 이 아니므로 `@Mock` 가능.
백오프는 `Duration.ZERO` 로 두어 테스트가 잠들지 않게 한다.

```java
@ExtendWith(MockitoExtension.class)
class OrderCreateRetrierTest {

    @Mock
    OrderService orderService;

    SimpleMeterRegistry registry;
    OrderCreateRetrier retrier;
    AuthenticatedUser user;
    CreateOrderRequest request;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        OrderProperties properties = new OrderProperties(
                Duration.ofMinutes(15), new OrderProperties.Retry(3, Duration.ZERO));
        retrier = new OrderCreateRetrier(orderService, properties, registry);
        user = new AuthenticatedUser("uid", UserType.CONSUMER, "nick");
        request = new CreateOrderRequest("item-uid", 1);
    }

    private static ObjectOptimisticLockingFailureException conflict() {
        return new ObjectOptimisticLockingFailureException(StoresItems.class, 1L);
    }

    private double count(String outcome) {
        return registry.get("order.create.attempts").tag("outcome", outcome).counter().count();
    }
}
```

케이스 다섯 개 (todo 5번 그대로):

| 테스트 | given | then |
|---|---|---|
| 두 번 충돌 후 성공 | `willThrow(conflict()).willThrow(conflict()).willReturn(response)` | `create` 3회 호출, 응답 반환, `success`=1, `conflict`=2, `exhausted`=0, `attempts.used` count 1 · max 3 |
| 상한 초과 | 세 번 모두 `conflict()` | `ObjectOptimisticLockingFailureException` 그대로 전파, 3회 호출, `conflict`=3, `exhausted`=1, `success`=0 |
| `InsufficientStock` 은 즉시 전파 | `willThrow(new OrderException.InsufficientStock(0))` | 1회 호출, `conflict`=0, `exhausted`=0 |
| 첫 시도 성공 | `willReturn(response)` | 1회 호출, `attempts.used` max 1 |
| 상한 1 이면 재시도 없음 | `Retry(1, ZERO)` + `conflict()` 한 번 | 1회 호출, `exhausted`=1 |

`response` 는 `OrderServiceTest` 의 fixture 를 그대로 가져와 `OrderResponse.from(new UsersOrders(account, item, 1))` 로
만들면 된다. 호출 횟수 검증은 `then(orderService).should(times(3)).create(user, request, null)`.

컨트롤러가 새 빈을 호출하는지는 `SecurityConfigTest` 처럼 슬라이스로 확인할 수도 있지만, 생성자 교체는 컴파일러가
잡아 주므로 이번에는 넣지 않아도 된다.

실행:

```powershell
$env:JAVA_HOME = "$HOME\.jdks\azul-21.0.11"
.\gradlew.bat :ieum-api:test --tests "com.hwannee.ieum.orders.*" --no-daemon
```

---

## 6. 측정 절차

1. `.env` 에 `STOCK_STRATEGY=optimistic`, `SQL_LOG_LEVEL=warn`, `SQL_BIND_LOG_LEVEL=off`. 재시도 설정은 기본값(3, 10ms) 으로 첫 라운드
2. `reset-loadtest.sql` 로 재고 100 · 주문 0 · `version` 0 확인 (09-14 재측정 뒤 실행해 둔 상태)
3. 두 서버 기동. `curl localhost:8080/actuator/prometheus | grep order_create` 로 카운터가 0 으로 등록됐는지 확인
4. 4.4 의 스크랩 루프 시작
5. `k6 run scripts/k6/create-order.js`
6. 스크랩 루프 정지, 최종 누적값 한 번 더 긁기
7. DB 확인 쿼리 (1단계와 같은 두 개 + 아래 하나)

```sql
SELECT MAX(id) - COUNT(*) AS rolled_back_inserts FROM users_orders;
```

8. `performance/<날짜>-stage2-optimistic.md` 를 1단계와 같은 형식으로 작성. 표에 넣을 것:

| 항목 | 출처 |
|---|---|
| 201 / 409 / 503 건수 | k6 체크 |
| 최종 `remaining_quantity`, `PENDING` 건수 (둘 다 100 / 0 이어야 함) | DB |
| `exhausted` 건수와 그중 재고가 남아 있던 시점의 비율 | `order_create_attempts_total`, k6 503 시각 분포 |
| 시도 횟수 분포 (`le="1"`, `le="2"`, `le="3"`) | `order_create_attempts_used_bucket` |
| 충돌 시도 총수 = 롤백된 INSERT 수 | `conflict` 카운터, `MAX(id) - COUNT(*)` |
| `hikaricp_connections_pending` 최댓값, `acquire_seconds_max` | 스크랩 로그 |
| 예약 p99, 처리량 (예약 구간 시간으로 계산) | k6 |

9. ADR-0003 결과 표 2단계 행 갱신. 결정 절에 "Version 으로 해결되는 것과 남는 것" 을 수치와 함께:
   - 해결: 초과 예약 0 (201 정확히 100, 불변식 성립)
   - 남는 것: 재고가 있는데 답을 못 준 소진 실패(503 건수), 실패 시도의 DB 비용(롤백된 INSERT 수 · 커넥션 대기),
     재고 행 밖의 불변식(중복 활성 예약 검사는 여전히 DB 조회 기반), 처리량 상한(한 행을 두고 직렬화되는 커밋)
10. 시간이 되면 `STOCK_RETRY_MAX_ATTEMPTS=10` 으로 한 라운드 더. 소진 실패는 줄고 p99·pending 은 오르는 트레이드오프가
    표 한 줄로 보인다
11. `reset-loadtest.sql`

---

## 7. 함정

- **`@Transactional` 안에서 catch 하지 않는다.** `OrderService` 안에서 잡으면 이미 rollback-only 인 트랜잭션이라
  두 번째 시도의 커밋이 `UnexpectedRollbackException` 으로 실패한다. 재시도 빈에는 `@Transactional` 을 붙이지 않는다
- **예외 타입.** 커밋 시점 번역은 `ObjectOptimisticLockingFailureException` 또는 하위 `JpaOptimisticLockingFailureException`.
  중간에 `flush()` 를 명시적으로 호출하는 경로가 생기면 `OptimisticLockingFailureException` 계열의 다른 하위 타입이
  나올 수 있으므로 부모를 잡는다
- **`OptimisticLockStockDeduction.deduct` 의 `@Transactional` 은 REQUIRED** 라 `create` 의 트랜잭션에 참여한다.
  전략 메서드 안에서 예외가 나지 않는 것이 정상이며, 충돌은 항상 `create` 프록시 밖에서 난다.
  단위 테스트에서 전략을 직접 호출해 충돌을 재현하려 하지 말 것
- **`InsufficientStock` 은 stale 값으로 판정될 수 있다.** remaining 1 을 읽은 두 스레드 중 하나는 충돌, 다른 하나는
  성공. 충돌한 쪽은 재시도에서 0 을 읽고 409. 이 순서가 맞다. 재시도에서 `InsufficientStock` 이 나오면 즉시 끝나야 한다
- **Prometheus 엔드포인트가 401 이면** permitAll 줄을 빠뜨린 것. `management.endpoints.web.exposure.include` 를
  빠뜨리면 404. 둘 다 기본값이 닫혀 있다
- **`pending` 게이지는 실행 중에만 값이 있다.** 측정 후 스냅샷은 0. 4.4 의 루프를 k6 시작 전에 켠다
- **1단계와 같은 방식으로 기동한다.** 09-14 재측정은 `bootJar` + `java -jar` 였다. `bootRun` 은 devtools 가 붙어
  재시작 클래스로더가 끼므로 지연 비교에는 jar 또는 IntelliJ 실행 중 하나로 통일한다
- **k6 체크를 추가하지 않으면** 503 이 두 체크 모두에서 빠져 "401/500" 으로 오해된다. 3절의 한 줄을 먼저 넣는다
- **JWKS.** 두 서버를 재기동하면 임시 키가 바뀌므로 k6 setup 이 매번 새로 로그인하는 것이 맞다. 토큰을 파일로 뽑아
  재사용하는 방식으로 바꾸면 이 점을 같이 고려해야 한다
- **`ThreadLocalRandom.nextLong(bound)` 는 bound 가 0 이면 예외.** 백오프가 `ZERO` 인 테스트에서 터지지 않도록
  1.2 의 `upperBound <= 0` 분기를 빼먹지 않는다

---

## 8. 커밋 단위

파일당 하나씩. 순서 예시:

1. `feat: add retry settings to OrderProperties` (+ yaml, `.env.example` 은 각각 별도 커밋)
2. `feat: add OrderCreateRetrier wrapping OrderService.create with optimistic lock retry`
3. `refactor: route order creation through OrderCreateRetrier in OrderController`
4. `feat: respond 503 with Retry-After when optimistic lock retries are exhausted`
5. `build: add micrometer prometheus registry` / `feat: expose prometheus endpoint`
6. `test: add OrderCreateRetrierTest`
7. `test: count 503 contention responses in k6 create-order scenario`
8. `docs: record stage 2 optimistic lock load test results` / ADR / todo
