package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.config.OrderProperties;
import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.repository.UsersOrdersRepository;
import com.hwannee.ieum.orders.stock.RedisStockDeduction.Outcome;
import com.hwannee.ieum.orders.stock.RedisStockDeduction.Reservation;
import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.UsersAccount;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RedisStockDeductionTest {

    private static final long ITEM_ID = 10L;
    private static final String USER = "consumer-uid";
    private static final String KEY = "idem-key";
    private static final List<String> RESERVE_KEYS = List.of(
            StockKeys.stock(ITEM_ID), StockKeys.active(ITEM_ID), StockKeys.idempotency(ITEM_ID, USER, KEY));

    @Mock
    StringRedisTemplate redis;

    @Mock
    StoresItemsRepository items;

    @Mock
    UsersOrdersRepository orders;

    SimpleMeterRegistry registry;
    RedisStockDeduction strategy;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        OrderProperties properties = new OrderProperties(Duration.ofMinutes(15),
                new OrderProperties.Retry(3, Duration.ofMillis(10)),
                new OrderProperties.Idempotency(Duration.ofDays(1), Duration.ofSeconds(30)), Duration.ofSeconds(5));
        strategy = new RedisStockDeduction(redis, items, orders, properties, registry);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 스크립트가_OK_면_RESERVED() {
        givenReserve("OK");

        Reservation reservation = strategy.reserve(ITEM_ID, USER, KEY, 1);

        assertThat(reservation.outcome()).isEqualTo(Outcome.RESERVED);
        assertThat(reserved("reserved")).isEqualTo(1);
    }

    @Test
    void 스크립트_결과를_거절_사유로_옮긴다() {
        givenReserve("DUPLICATE", "SOLD_OUT", "REPLAY:PENDING", "REPLAY:42");

        assertThat(strategy.reserve(ITEM_ID, USER, KEY, 1).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(strategy.reserve(ITEM_ID, USER, KEY, 1).outcome()).isEqualTo(Outcome.SOLD_OUT);
        assertThat(strategy.reserve(ITEM_ID, USER, KEY, 1).outcome()).isEqualTo(Outcome.IN_FLIGHT);
        assertThat(strategy.reserve(ITEM_ID, USER, KEY, 1)).isEqualTo(new Reservation(Outcome.REPLAY, 42L));
    }

    @Test
    void 재고_키가_없으면_DB_주문_상태로_워밍업한_뒤_한_번_더_판정한다() {
        givenReserve("MISSING", "OK");
        given(items.findById(ITEM_ID)).willReturn(Optional.of(item(100)));
        given(orders.sumQuantityByItemAndStates(eq(ITEM_ID), anyCollection())).willReturn(7L);
        given(orders.findAccountUidsByItemAndStates(ITEM_ID, OrderState.ACTIVE)).willReturn(List.of("a", "b"));

        Reservation reservation = strategy.reserve(ITEM_ID, USER, KEY, 1);

        assertThat(reservation.outcome()).isEqualTo(Outcome.RESERVED);
        then(redis).should().execute(any(RedisScript.class),
                eq(List.of(StockKeys.stock(ITEM_ID), StockKeys.active(ITEM_ID))), eq("93"), eq("a"), eq("b"));
    }

    @Test
    void 워밍업_후에도_키가_없으면_IllegalStateException() {
        givenReserve("MISSING", "MISSING");
        given(items.findById(ITEM_ID)).willReturn(Optional.of(item(100)));

        assertThatThrownBy(() -> strategy.reserve(ITEM_ID, USER, KEY, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deduct_는_redis_전략에서_쓰지_않는다() {
        assertThatThrownBy(() -> strategy.deduct(ITEM_ID, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 트랜잭션_안의_복구는_커밋_뒤에_나간다() {
        TransactionSynchronizationManager.initSynchronization();

        strategy.restore(order(77L));

        then(redis).should(never()).execute(any(RedisScript.class), any(List.class), any(), any(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        then(redis).should().execute(any(RedisScript.class),
                eq(List.of(StockKeys.stock(ITEM_ID), StockKeys.active(ITEM_ID), StockKeys.restored(ITEM_ID, 77L))),
                eq("1"), eq(USER), eq(String.valueOf(Duration.ofDays(1).toSeconds())));
    }

    @Test
    void 이미_복구된_주문이면_건너뛴다() {
        given(redis.execute(any(RedisScript.class), any(List.class), any(), any(), any())).willReturn(0L);

        strategy.restore(order(77L));

        assertThat(event("release_skipped")).isEqualTo(1);
        assertThat(event("released")).isZero();
    }

    @Test
    void 복구가_실패해도_예외를_던지지_않고_카운터만_올린다() {
        given(redis.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
                .willThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> strategy.restore(order(77L))).doesNotThrowAnyException();
        assertThat(event("release_failed")).isEqualTo(1);
    }

    private void givenReserve(String first, String... rest) {
        given(redis.execute(any(RedisScript.class), eq(RESERVE_KEYS), eq("1"), eq(USER), eq("30")))
                .willReturn(first, (Object[]) rest);
    }

    private double reserved(String outcome) {
        return registry.get("stock.redis.reserve").tag("outcome", outcome).counter().count();
    }

    private double event(String event) {
        return registry.get("stock.redis.event").tag("event", event).counter().count();
    }

    private StoresItems item(int initial) {
        UsersAccount owner = new UsersAccount(null, UserType.BUSINESS_OWNER, "owner-uid", "owner", "encoded");
        Stores store = new Stores(owner, "store-uid", "가게", StoreType.CAFE, LocalTime.of(9, 0), LocalTime.of(18, 0));
        StoresItems item = new StoresItems(store, "item-uid", null, "빵", 5000, 3000, initial,
                LocalDateTime.now().plusHours(1));
        ReflectionTestUtils.setField(item, "id", ITEM_ID);
        return item;
    }

    private UsersOrders order(long orderId) {
        UsersAccount consumer = new UsersAccount(null, UserType.CONSUMER, USER, "nick", "encoded");
        UsersOrders order = new UsersOrders(consumer, item(100), 1, KEY);
        ReflectionTestUtils.setField(order, "id", orderId);
        return order;
    }
}
