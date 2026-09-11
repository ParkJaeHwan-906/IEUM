package com.hwannee.ieum.orders.web;

import com.hwannee.ieum.auth.verify.principal.AuthenticatedUser;
import com.hwannee.ieum.auth.verify.principal.CurrentUser;
import com.hwannee.ieum.orders.service.OrderService;
import com.hwannee.ieum.orders.web.dto.OrderResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    // TODO(2.2 픽업 코드): 요청 본문으로 픽업 코드를 받아 검증
    @PostMapping("/{orderId}/pickup")
    public OrderResponse pickUp(@CurrentUser AuthenticatedUser owner, @PathVariable Long orderId) {
        return orderService.pickUp(owner, orderId);
    }

    // TODO(2.2 점주 취소): POST /{orderId}/reject — PENDING 거절. 재고 복구 포함
    // TODO(점주 기능): GET /api/owner/items/{itemUid}/orders — 상품별 예약 현황 (README Merchant 기능)
}
