package com.hwannee.ieum.orders.stock;

import com.hwannee.ieum.orders.exception.OrderException;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.stores.repository.StoresItemsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 2단계: @Version 낙관적 락. 엔티티를 수정하고 더티 체킹으로 UPDATE ... WHERE version = ? 를 태운다.
// 충돌은 이 메서드 안이 아니라 감싸는 트랜잭션의 커밋 시점에 ObjectOptimisticLockingFailureException 으로 난다.
//
// TODO(2단계 재시도): 재시도는 반드시 트랜잭션 바깥에서 새 트랜잭션으로 해야 한다.
//   @Transactional 메서드 안에서 catch 하고 다시 호출하면 이미 rollback-only 로 표시된 같은 트랜잭션이라 실패한다.
//   후보: OrderService.create 를 감싸는 별도 빈(OrderCreateRetry)에서 spring-retry 또는 수동 루프. 최대 횟수·백오프는 설정값
// TODO(측정): 재시도 횟수, 최종 실패율, DB 커넥션 대기(HikariCP pending) 를 Micrometer 로 남긴다
@Component
@ConditionalOnProperty(name = "ieum.stock.strategy", havingValue = "optimistic")
public class OptimisticLockStockDeduction implements StockDeductionStrategy {

    private final StoresItemsRepository items;

    public OptimisticLockStockDeduction(StoresItemsRepository items) {
        this.items = items;
    }

    @Override
    @Transactional
    public void deduct(Long itemId, int quantity) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        if (item.getRemainingQuantity() < quantity) {
            throw new OrderException.InsufficientStock(item.getRemainingQuantity());
        }
        item.decreaseQuantity(quantity);
    }

    @Override
    @Transactional
    public void restore(Long itemId, int quantity) {
        StoresItems item = items.findById(itemId).orElseThrow(OrderException.ItemNotFound::new);
        item.increaseQuantity(quantity);
    }
}
