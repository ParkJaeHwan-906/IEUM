package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 1단계: 잠금 없음. 조회한 값으로 계산해 덮어쓴다.
// 두 트랜잭션이 같은 remaining 을 읽으면 둘 다 remaining - qty 를 쓰므로 한 건의 차감이 사라지고 초과 예약이 난다.
// 이 초과 예약이 실제로 발생하는 것을 k6 로 먼저 기록하는 것이 이 구현체의 존재 이유다.
@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "naive", matchIfMissing = true)
public class NaiveStockDeduction implements StockDeductionStrategy {

    private final StoresItemsRepository items;

    public NaiveStockDeduction(StoresItemsRepository items) {
        this.items = items;
    }

    @Override
    @Transactional
    public void deduct(Long itemId, int quantity) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        int remaining = item.getRemainingQuantity();
        if (remaining < quantity) {
            throw new OrderException.InsufficientStock(remaining);
        }
        // TODO(측정): 여기서 Thread.sleep 몇 ms 를 넣으면 경합 창이 넓어져 초과 예약이 더 잘 재현된다. 측정 시에만 켠다
        items.overwriteRemainingQuantity(itemId, remaining - quantity);
    }

    @Override
    @Transactional
    public void restore(Long itemId, int quantity) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        int restored = Math.min(item.getRemainingQuantity() + quantity, item.getInitialQuantity());
        items.overwriteRemainingQuantity(itemId, restored);
    }
}
