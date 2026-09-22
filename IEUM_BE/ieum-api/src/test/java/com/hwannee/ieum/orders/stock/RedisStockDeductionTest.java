package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.exception.OrderException;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RedisStockDeductionTest {

    private static final long ITEM_ID = 10L;
    private static final String KEY = "stock:10";

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> ops;

    @Mock
    StoresItemsRepository items;

    SimpleMeterRegistry registry;
    RedisStockDeduction strategy;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        strategy = new RedisStockDeduction(redis, items, registry);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 재고가_있으면_스크립트_한_번으로_끝난다() {
        givenScript(5L);

        assertThatCode(() -> strategy.deduct(ITEM_ID, 1)).doesNotThrowAnyException();

        then(redis).should(times(1)).execute(any(RedisScript.class), eq(List.of(KEY)), eq("1"));
        then(redis).should(never()).opsForValue();
        assertThat(count("success")).isEqualTo(1);
        assertThat(count("insufficient")).isZero();
        assertThat(count("warmup")).isZero();
    }

    @Test
    void 부족하면_InsufficientStock_이고_메시지에_숫자가_없다() {
        givenScript(-1L);

        assertThatThrownBy(() -> strategy.deduct(ITEM_ID, 1))
                .isInstanceOf(OrderException.InsufficientStock.class)
                .hasMessage("재고가 부족합니다.");

        assertThat(count("insufficient")).isEqualTo(1);
        assertThat(count("success")).isZero();
    }

    @Test
    void 키가_없으면_DB_값으로_SET_NX_후_한_번_더_실행한다() {
        givenScript(-2L, 9L);
        given(redis.opsForValue()).willReturn(ops);
        given(items.findById(ITEM_ID)).willReturn(Optional.of(item(10)));

        assertThatCode(() -> strategy.deduct(ITEM_ID, 1)).doesNotThrowAnyException();

        then(ops).should().setIfAbsent(KEY, "10");
        then(redis).should(times(2)).execute(any(RedisScript.class), eq(List.of(KEY)), eq("1"));
        assertThat(count("warmup")).isEqualTo(1);
        assertThat(count("success")).isEqualTo(1);
    }

    @Test
    void 워밍업_후에도_키가_없으면_IllegalStateException() {
        givenScript(-2L, -2L);
        given(redis.opsForValue()).willReturn(ops);
        given(items.findById(ITEM_ID)).willReturn(Optional.of(item(10)));

        assertThatThrownBy(() -> strategy.deduct(ITEM_ID, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 워밍업_시_상품이_없으면_ItemNotFound() {
        givenScript(-2L);
        given(items.findById(ITEM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> strategy.deduct(ITEM_ID, 1))
                .isInstanceOf(OrderException.ItemNotFound.class);

        then(redis).should(never()).opsForValue();
    }

    @Test
    void 트랜잭션이_롤백되면_보상_INCRBY() {
        TransactionSynchronizationManager.initSynchronization();
        givenScript(5L);
        given(redis.opsForValue()).willReturn(ops);

        strategy.deduct(ITEM_ID, 1);
        then(ops).should(never()).increment(anyString(), eq(1L));

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        then(ops).should().increment(KEY, 1L);
        assertThat(count("compensated")).isEqualTo(1);
    }

    @Test
    void 커밋되면_보상하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        givenScript(5L);

        strategy.deduct(ITEM_ID, 1);
        complete(TransactionSynchronization.STATUS_COMMITTED);

        then(redis).should(never()).opsForValue();
        assertThat(count("compensated")).isZero();
    }

    @Test
    void 커밋_결과를_모르면_보상하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        givenScript(5L);

        strategy.deduct(ITEM_ID, 1);
        complete(TransactionSynchronization.STATUS_UNKNOWN);

        then(redis).should(never()).opsForValue();
    }

    @Test
    void 부족으로_끝나면_롤백돼도_보상하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        givenScript(-1L);

        assertThatThrownBy(() -> strategy.deduct(ITEM_ID, 1))
                .isInstanceOf(OrderException.InsufficientStock.class);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void 트랜잭션_밖_restore_는_즉시_INCRBY() {
        given(redis.opsForValue()).willReturn(ops);

        strategy.restore(ITEM_ID, 2);

        then(ops).should().increment(KEY, 2L);
    }

    @Test
    void 트랜잭션_안_restore_는_커밋_후_INCRBY() {
        TransactionSynchronizationManager.initSynchronization();
        given(redis.opsForValue()).willReturn(ops);

        strategy.restore(ITEM_ID, 2);
        then(ops).should(never()).increment(KEY, 2L);

        commit();

        then(ops).should().increment(KEY, 2L);
    }

    @Test
    void 트랜잭션_안_restore_는_롤백되면_실행되지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        strategy.restore(ITEM_ID, 2);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        then(redis).should(never()).opsForValue();
    }

    @Test
    void initialize_는_커밋_후_SET() {
        TransactionSynchronizationManager.initSynchronization();
        given(redis.opsForValue()).willReturn(ops);

        strategy.initialize(ITEM_ID, 100);
        then(ops).should(never()).set(KEY, "100");

        commit();

        then(ops).should().set(KEY, "100");
    }

    private void givenScript(Long first, Long... rest) {
        given(redis.execute(any(RedisScript.class), eq(List.of(KEY)), eq("1"))).willReturn(first, rest);
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        complete(TransactionSynchronization.STATUS_COMMITTED);
    }

    private static void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));
    }

    private double count(String outcome) {
        return registry.get("stock.redis.deduct").tag("outcome", outcome).counter().count();
    }

    private static StoresItems item(int remaining) {
        UsersAccount owner = new UsersAccount(null, UserType.BUSINESS_OWNER, "owner-uid", "owner", "encoded");
        Stores store = new Stores(owner, "store-uid", "가게", StoreType.CAFE, LocalTime.of(9, 0), LocalTime.of(18, 0));
        StoresItems item = new StoresItems(store, "item-uid", null, "빵", 5000, 3000, 100, LocalDateTime.now().plusHours(1));
        ReflectionTestUtils.setField(item, "id", ITEM_ID);
        ReflectionTestUtils.setField(item, "remainingQuantity", remaining);
        return item;
    }
}
