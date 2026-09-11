package com.hwannee.ieum.orders.web.dto;

import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;

import java.time.LocalDateTime;

// TODO(노출 범위): 주문에 uid 가 없어 내부 PK 를 그대로 내보낸다. UsersOrders 에 uid 컬럼을 두고 바꿀지 결정
public record OrderResponse(
        Long orderId,
        String itemUid,
        String itemName,
        int quantity,
        int orderPrice,
        OrderState state,
        LocalDateTime readyAt,
        LocalDateTime createdAt
) {
    public static OrderResponse from(UsersOrders order) {
        return new OrderResponse(
                order.getId(),
                order.getStoresItem().getUid(),
                order.getStoresItem().getName(),
                order.getQuantity(),
                order.getOrderPrice(),
                order.getOrderState(),
                order.getReadyAt(),
                order.getCreatedAt()
        );
    }
}
