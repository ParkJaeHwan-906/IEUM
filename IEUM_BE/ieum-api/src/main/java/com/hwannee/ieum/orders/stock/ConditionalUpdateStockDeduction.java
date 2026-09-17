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
