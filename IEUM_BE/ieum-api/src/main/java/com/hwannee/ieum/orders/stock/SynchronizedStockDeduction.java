package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "synchronized")
public class SynchronizedStockDeduction implements StockDeductionStrategy {

    public enum Scope { CREATE, DEDUCT }

    private final StoresItemsRepository items;
    private final Scope scope;
    private final Object monitor = new Object();

    public SynchronizedStockDeduction(StoresItemsRepository items,
                                      @Value("${ieum.stock.synchronized-scope:create}") String scope) {
        this.items = items;
        this.scope = Scope.valueOf(scope.trim().toUpperCase());
    }

    @Override
    @Transactional
    public void deduct(Long itemId, int quantity) {
        if (scope == Scope.DEDUCT) {
            synchronized (monitor) {
                overwrite(itemId, quantity);
            }
            return;
        }
        overwrite(itemId, quantity);
    }

    @Override
    @Transactional
    public void restore(Long itemId, int quantity) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        int restored = Math.min(item.getRemainingQuantity() + quantity, item.getInitialQuantity());
        items.overwriteRemainingQuantity(itemId, restored);
    }

    @Override
    public <T> T serialize(Supplier<T> action) {
        if (scope != Scope.CREATE) {
            return action.get();
        }
        synchronized (monitor) {
            return action.get();
        }
    }

    private void overwrite(Long itemId, int quantity) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        int remaining = item.getRemainingQuantity();
        if (remaining < quantity) {
            throw new OrderException.InsufficientStock(remaining);
        }
        items.overwriteRemainingQuantity(itemId, remaining - quantity);
    }
}
