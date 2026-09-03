package com.hwannee.ieum.orders.domain;

public enum OrderState {
    PENDING,                // 처리중
    APPROVED,               // 주문 승인
    READY_FOR_PICKUP,       // 픽업 준비 완료
    PICKED_UP,              // 픽업 완료
    CANCELED                // 취소
}
