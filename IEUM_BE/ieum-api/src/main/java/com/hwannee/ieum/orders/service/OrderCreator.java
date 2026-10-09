package com.hwannee.ieum.orders.service;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;

public interface OrderCreator {

    OrderResponse create(AuthenticatedUser user, CreateOrderRequest request, String idempotencyKey);
}
