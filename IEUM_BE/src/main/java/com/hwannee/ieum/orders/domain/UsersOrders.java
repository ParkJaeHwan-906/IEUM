package com.hwannee.ieum.orders.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import com.hwannee.ieum.stores.domain.StoresItems;
import com.hwannee.ieum.users.domain.UsersAccount;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "users_orders")
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

    @Version
    private Long version;

    public UsersOrders(UsersAccount usersAccount, StoresItems storesItem, Integer quantity) {
        this.usersAccount = usersAccount;
        this.storesItem = storesItem;
        this.quantity = quantity;
        this.orderPrice = storesItem.getSalePrice();
        this.orderState = OrderState.PENDING;
    }

    public void approve() {
        this.orderState = OrderState.APPROVED;
    }

    public void readyForPickup() {
        this.orderState = OrderState.READY_FOR_PICKUP;
    }

    public void pickUp() {
        this.orderState = OrderState.PICKED_UP;
    }

    public void cancel() {
        this.orderState = OrderState.CANCELED;
    }
}
