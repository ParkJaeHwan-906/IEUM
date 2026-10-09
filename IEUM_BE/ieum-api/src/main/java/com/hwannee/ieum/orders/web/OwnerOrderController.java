package com.hwannee.ieum.orders.web;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.auth.verify.principal.CurrentUser;
import com.hwannee.ieum.orders.service.OrderService;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import com.hwannee.ieum.orders.web.dto.PickupRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 역할 검사는 여기(@PreAuthorize), 소유권 검사는 서비스 계층(OrderService.ownedByStoreOwner). ADR-0001 의 두 층
@RestController
@RequestMapping("/api/owner/orders")
@PreAuthorize("hasRole('BUSINESS_OWNER')")
public class OwnerOrderController {

    private final OrderService orderService;

    public OwnerOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/{orderId}/approve")
    public OrderResponse approve(@CurrentUser AuthenticatedUser owner, @PathVariable Long orderId) {
        return orderService.approve(owner, orderId);
    }

    @PostMapping("/{orderId}/ready")
    public OrderResponse ready(@CurrentUser AuthenticatedUser owner, @PathVariable Long orderId) {
        return orderService.readyForPickup(owner, orderId);
    }

    @PostMapping("/{orderId}/pickup")
    public OrderResponse pickUp(@CurrentUser AuthenticatedUser owner, @PathVariable Long orderId,
                                @Valid @RequestBody PickupRequest request) {
        return orderService.pickUp(owner, orderId, request.pickupCode());
    }

    @PostMapping("/{orderId}/reject")
    public OrderResponse reject(@CurrentUser AuthenticatedUser owner, @PathVariable Long orderId) {
        return orderService.reject(owner, orderId);
    }

    // TODO(점주 기능): GET /api/owner/items/{itemUid}/orders — 상품별 예약 현황 (README Merchant 기능)
}
