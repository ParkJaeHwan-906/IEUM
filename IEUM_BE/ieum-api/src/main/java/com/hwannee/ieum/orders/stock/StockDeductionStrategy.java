package com.hwannee.ieum.orders.stock;

// 재고 차감·복구 전략. ieum.stock.strategy 값(naive | optimistic | redis)으로 구현체 하나만 빈으로 올라간다.
// 세 구현체를 같은 시나리오(재고 100 / 요청 10,000)로 돌려 비교하는 것이 TODO 2.1 의 목적이다.
public interface StockDeductionStrategy {

    void deduct(Long itemId, int quantity);

    void restore(Long itemId, int quantity);
}
