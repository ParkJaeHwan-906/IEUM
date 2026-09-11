package com.hwannee.ieum.orders.domain;

import java.util.EnumSet;
import java.util.Set;

public enum OrderState {
    PENDING,
    APPROVED,
    READY_FOR_PICKUP,
    PICKED_UP,
    CANCELED,
    EXPIRED;

    public static final Set<OrderState> ACTIVE = EnumSet.of(PENDING, APPROVED, READY_FOR_PICKUP);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
