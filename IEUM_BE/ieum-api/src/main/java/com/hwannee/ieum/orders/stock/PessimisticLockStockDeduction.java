package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "pessimistic")
public class PessimisticLockStockDeduction implements StockDeductionStrategy {

    private final StoresItemsRepository items;

    public PessimisticLockStockDeduction(StoresItemsRepository items) {
        this.items = items;
    }

    @Override
    public boolean locksItemRow() {
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deduct(Long itemId, int quantity) {
        StoresItems item = items.findByIdForUpdate(itemId).orElseThrow(OrderException.ItemNotFound::new);
        if (item.getRemainingQuantity() < quantity) {
            throw new OrderException.InsufficientStock(item.getRemainingQuantity());
        }
        item.decreaseQuantity(quantity);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(UsersOrders order) {
        StoresItems item = items.findByIdForUpdate(order.getStoresItem().getId())
                .orElseThrow(OrderException.ItemNotFound::new);
        item.increaseQuantity(order.getQuantity());
    }
}
