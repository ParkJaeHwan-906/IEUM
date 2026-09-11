package com.hwannee.ieum.orders.domain;

public class InvalidOrderStateException extends IllegalStateException {

    private final OrderState current;
    private final String action;

    public InvalidOrderStateException(OrderState current, String action) {
        super(current + " 상태에서는 " + action + " 할 수 없습니다.");
        this.current = current;
        this.action = action;
    }

    public OrderState current() {
        return current;
    }

    public String action() {
        return action;
    }
}
