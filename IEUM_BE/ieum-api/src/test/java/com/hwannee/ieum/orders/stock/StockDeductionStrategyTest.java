package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.stores.domain.StoreType;
import com.hwannee.ieum.stores.domain.Stores;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.users.domain.UserType;
import com.hwannee.ieum.users.domain.UsersAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class StockDeductionStrategyTest {

    private final StockDeductionStrategy strategy = new StockDeductionStrategy() {
        @Override
        public void deduct(Long itemId, int quantity) {
        }

        @Override
        public void restore(UsersOrders order) {
        }
    };

    StoresItems item;

    @BeforeEach
    void setUp() {
        UsersAccount owner = new UsersAccount(null, UserType.BUSINESS_OWNER, "owner-uid", "owner", "encoded");
        Stores store = new Stores(owner, "store-uid", "가게", StoreType.CAFE, LocalTime.of(9, 0), LocalTime.of(18, 0));
        item = new StoresItems(store, "item-uid", null, "빵", 5000, 3000, 100, LocalDateTime.now().plusHours(1));
        item.decreaseQuantity(30);
    }

    @Test
    void 늘리면_남은_수량도_같은_폭으로_는다() {
        assertThat(strategy.adjust(item, 120)).isTrue();

        assertThat(item.getInitialQuantity()).isEqualTo(120);
        assertThat(item.getRemainingQuantity()).isEqualTo(90);
        assertThat(item.heldQuantity()).isEqualTo(30);
    }

    @Test
    void 잡힌_수량까지는_줄일_수_있다() {
        assertThat(strategy.adjust(item, 30)).isTrue();

        assertThat(item.getInitialQuantity()).isEqualTo(30);
        assertThat(item.getRemainingQuantity()).isZero();
    }

    @Test
    void 잡힌_수량보다_줄이면_거절하고_그대로_둔다() {
        assertThat(strategy.adjust(item, 29)).isFalse();

        assertThat(item.getInitialQuantity()).isEqualTo(100);
        assertThat(item.getRemainingQuantity()).isEqualTo(70);
    }
}
