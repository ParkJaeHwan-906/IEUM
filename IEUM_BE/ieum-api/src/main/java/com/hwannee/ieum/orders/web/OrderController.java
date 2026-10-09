package com.hwannee.ieum.orders.web;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.auth.verify.principal.CurrentUser;
import com.hwannee.ieum.orders.service.OrderCreator;
import com.hwannee.ieum.orders.service.OrderService;
import com.hwannee.ieum.orders.web.dto.CreateOrderRequest;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderCreator orderCreator;

    public OrderController(OrderService orderService, OrderCreator orderCreator) {
        this.orderService = orderService;
        this.orderCreator = orderCreator;
    }

    // Idempotency-Key 는 필수 헤더. 없으면 MissingRequestHeaderException → 400 ProblemDetail
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CONSUMER')")
    public OrderResponse create(@CurrentUser AuthenticatedUser user,
                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @Valid @RequestBody CreateOrderRequest request) {
        return orderCreator.create(user, request, idempotencyKey);
    }

    @GetMapping("/me")
    public List<OrderResponse> mine(@CurrentUser AuthenticatedUser user) {
        return orderService.findMine(user);
    }

    // TODO(2.2 정책): READY_FOR_PICKUP 상태에서 소비자 취소를 허용할지. 현재는 활성 상태 전부 허용
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@CurrentUser AuthenticatedUser user, @PathVariable Long orderId) {
        return orderService.cancel(user, orderId);
    }
}
