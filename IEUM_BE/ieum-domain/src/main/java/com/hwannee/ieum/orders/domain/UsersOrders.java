package com.hwannee.ieum.orders.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.users.domain.UsersAccount;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "users_orders",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_users_orders_account_idempotency_key", columnNames = {"user_account_id", "idempotency_key"}),
        indexes = @Index(name = "idx_users_orders_state_created", columnList = "order_state, created_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsersOrders extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_account_id", nullable = false)
    private UsersAccount usersAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_item_id", nullable = false)
    private StoresItems storesItem;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "order_price", nullable = false, updatable = false)
    private Integer orderPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_state", length = 20, nullable = false)
    private OrderState orderState;

    // TODO(2.2 만료): 만료 판정 기준. readyForPickup() 에서만 기록되며 readyAt + PICKUP_TTL 이 만료 시각
    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    @Column(name = "idempotency_key", length = 64, updatable = false)
    private String idempotencyKey;

    @Column(name = "pickup_code", length = 6)
    private String pickupCode;

    @Version
    private Long version;

    public UsersOrders(UsersAccount usersAccount, StoresItems storesItem, Integer quantity, String idempotencyKey) {
        this.usersAccount = usersAccount;
        this.storesItem = storesItem;
        this.quantity = quantity;
        this.orderPrice = storesItem.getSalePrice();
        this.orderState = OrderState.PENDING;
        this.idempotencyKey = idempotencyKey;
    }

    public void approve() {
        require(OrderState.PENDING, "승인");
        this.orderState = OrderState.APPROVED;
    }

    public void readyForPickup(String pickupCode) {
        require(OrderState.APPROVED, "준비 완료 처리");
        this.orderState = OrderState.READY_FOR_PICKUP;
        this.readyAt = LocalDateTime.now();
        this.pickupCode = pickupCode;
    }

    public boolean matchesPickupCode(String candidate) {
        if (pickupCode == null || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(pickupCode.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    public void pickUp() {
        require(OrderState.READY_FOR_PICKUP, "픽업 완료 처리");
        this.orderState = OrderState.PICKED_UP;
    }

    public void cancel() {
        if (orderState != OrderState.PENDING && orderState != OrderState.APPROVED) {
            throw new InvalidOrderStateException(orderState, "취소");
        }
        this.orderState = OrderState.CANCELED;
    }

    public void reject() {
        require(OrderState.PENDING, "거절");
        this.orderState = OrderState.CANCELED;
    }

    public void cancelUnapproved() {
        require(OrderState.PENDING, "미승인 자동 취소");
        this.orderState = OrderState.CANCELED;
    }

    // TODO(2.2 만료): Expiry Worker 가 호출. READY_FOR_PICKUP 이 아니면(이미 픽업·취소) 예외 대신 false 를 돌려
    //   워커가 조용히 건너뛰게 할지, 예외로 둘지 결정. 현재는 다른 전이와 같은 규칙으로 예외
    public void expire() {
        require(OrderState.READY_FOR_PICKUP, "만료 처리");
        this.orderState = OrderState.EXPIRED;
    }

    public boolean isActive() {
        return orderState.isActive();
    }

    public boolean isExpiredAt(LocalDateTime now, Duration pickupTtl) {
        return orderState == OrderState.READY_FOR_PICKUP
                && readyAt != null
                && !now.isBefore(readyAt.plus(pickupTtl));
    }

    private void require(OrderState expected, String action) {
        if (orderState != expected) {
            throw new InvalidOrderStateException(orderState, action);
        }
    }
}
