package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.stores.domain.StoresItems;

import java.util.function.Supplier;

public interface StockDeductionStrategy {

    void deduct(Long itemId, int quantity);

    void restore(UsersOrders order);

    default void settle(UsersOrders order) {
    }

    default void initialize(Long itemId, int quantity) {
    }

    default boolean adjust(StoresItems lockedItem, int initialQuantity) {
        if (initialQuantity < lockedItem.heldQuantity()) {
            return false;
        }
        lockedItem.changeInitialQuantity(initialQuantity);
        return true;
    }

    default boolean locksItemRow() {
        return false;
    }

    default <T> T serialize(Supplier<T> action) {
        return action.get();
    }
}
