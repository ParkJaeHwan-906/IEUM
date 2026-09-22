package com.hwannee.ieum.orders.stock;

// 재고 차감·복구 전략. ieum.stock.strategy 값(naive | optimistic | conditional | redis)으로 구현체 하나만 빈으로 올라간다.
// initialize 는 상품 등록 직후 원장을 채우는 훅. DB 가 원장인 세 전략은 할 일이 없어 기본 메서드로 두고 redis 만 재정의한다.
// StoreItemService 가 특정 구현체를 알면 다른 전략으로 띄울 때 빈이 없어 기동에 실패하므로 인터페이스에 둔다.
public interface StockDeductionStrategy {

    void deduct(Long itemId, int quantity);

    void restore(Long itemId, int quantity);

    default void initialize(Long itemId, int quantity) {
    }
}
