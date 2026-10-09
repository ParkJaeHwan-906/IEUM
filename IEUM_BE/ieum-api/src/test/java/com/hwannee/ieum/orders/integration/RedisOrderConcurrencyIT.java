package com.hwannee.ieum.orders.integration;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.reconcile.StockReconciliationJob;
import com.hwannee.ieum.orders.stock.RedisStockDeduction;
import com.hwannee.ieum.orders.stock.StockKeys;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import com.hwannee.ieum.stores.domain.StoresItems;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "ieum.stock.strategy=redis")
class RedisOrderConcurrencyIT extends OrderConcurrencyScenario {

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisStockDeduction ledger;

    @Autowired
    StockReconciliationJob reconciliation;

    @Autowired
    TransactionTemplate tx;

    @Override
    boolean closesDuplicateGap() {
        return true;
    }

    @Override
    long ledgerRemaining(StoresItems item) {
        return Long.parseLong(redis.opsForValue().get(StockKeys.stock(item.getId())));
    }

    @Test
    void 같은_주문의_복구가_두_번_와도_재고는_한_번만_돌아온다() {
        StoresItems item = item(STOCK);
        AuthenticatedUser consumer = consumers(1).getFirst();
        OrderResponse created = creator.create(consumer, new CreateOrderRequest(item.getUid(), 1), UUID.randomUUID().toString());
        UsersOrders order = tx.execute(status -> {
            UsersOrders found = orders.findById(created.orderId()).orElseThrow();
            found.getUsersAccount().getUid();
            return found;
        });

        ledger.restore(order);
        ledger.restore(order);

        assertThat(ledgerRemaining(item)).isEqualTo(STOCK);
        assertThat(redis.opsForSet().isMember(StockKeys.active(item.getId()), consumer.uid())).isFalse();
    }

    @Test
    void 재고_키가_사라져도_DB_주문_상태로_다시_채운다() {
        StoresItems item = item(STOCK);
        AuthenticatedUser first = consumers(1).getFirst();
        creator.create(first, new CreateOrderRequest(item.getUid(), 1), UUID.randomUUID().toString());
        redis.delete(StockKeys.stock(item.getId()));
        redis.delete(StockKeys.active(item.getId()));

        assertThat(send(first, item, UUID.randomUUID().toString())).isEqualTo(Result.DUPLICATE);
        assertThat(ledgerRemaining(item)).isEqualTo(STOCK - 1);
    }

    @Test
    void 두_번_연속_같은_어긋남만_정정한다() {
        StoresItems item = item(STOCK);
        AuthenticatedUser consumer = consumers(1).getFirst();
        creator.create(consumer, new CreateOrderRequest(item.getUid(), 1), UUID.randomUUID().toString());
        redis.opsForValue().decrement(StockKeys.stock(item.getId()), 3);
        redis.opsForSet().add(StockKeys.active(item.getId()), "ghost");

        tx.executeWithoutResult(status -> invokeCheck(item));
        assertThat(ledgerRemaining(item)).isEqualTo(STOCK - 4);

        tx.executeWithoutResult(status -> invokeCheck(item));
        assertThat(ledgerRemaining(item)).isEqualTo(STOCK - 1);
        assertThat(redis.opsForSet().members(StockKeys.active(item.getId()))).containsExactly(consumer.uid());
        assertThat(items.findById(item.getId()).orElseThrow().getRemainingQuantity()).isEqualTo(STOCK - 1);
    }

    @Test
    void 두_대조_사이에_값이_바뀌면_정정하지_않는다() {
        StoresItems item = item(STOCK);
        ledger.initialize(item.getId(), STOCK);
        redis.opsForValue().decrement(StockKeys.stock(item.getId()), 2);

        tx.executeWithoutResult(status -> invokeCheck(item));
        redis.opsForValue().decrement(StockKeys.stock(item.getId()), 1);
        tx.executeWithoutResult(status -> invokeCheck(item));

        assertThat(ledgerRemaining(item)).isEqualTo(STOCK - 3);
    }

    private void invokeCheck(StoresItems item) {
        ReflectionTestUtils.invokeMethod(reconciliation, "check", item.getId());
    }
}
