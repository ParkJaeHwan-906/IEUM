package com.hwannee.ieum.stores.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "stores_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoresItems extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Stores store;

    @Column(length = 36, nullable = false, unique = true)
    private String uid;

    @Column(name = "item_img_url", length = 500)
    private String itemImgUrl;

    @Column(length = 50, nullable = false)
    private String name;

    @Column(name = "original_price", nullable = false)
    private Integer originalPrice;

    @Column(name = "sale_price", nullable = false)
    private Integer salePrice;

    @Column(name = "initial_quantity", nullable = false)
    private Integer initialQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private Integer remainingQuantity;

    @Column(name = "last_order_time", nullable = false)
    private LocalDateTime lastOrderTime;

    @Version
    private Long version;

    public StoresItems(Stores store, String uid, String itemImgUrl, String name,
                       Integer originalPrice, Integer salePrice, Integer initialQuantity,
                       LocalDateTime lastOrderTime) {
        this.store = store;
        this.uid = uid;
        this.itemImgUrl = itemImgUrl;
        this.name = name;
        this.originalPrice = originalPrice;
        this.salePrice = salePrice;
        this.initialQuantity = initialQuantity;
        this.remainingQuantity = initialQuantity;
        this.lastOrderTime = lastOrderTime;
    }

    public void decreaseQuantity(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("차감 수량은 1 이상이어야 합니다.");
        }
        if (remainingQuantity < amount) {
            throw new IllegalStateException("재고가 부족합니다. 남은 수량: " + remainingQuantity);
        }
        this.remainingQuantity -= amount;
    }

    public void increaseQuantity(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("복구 수량은 1 이상이어야 합니다.");
        }
        if (remainingQuantity + amount > initialQuantity) {
            throw new IllegalStateException("초기 수량을 초과할 수 없습니다.");
        }
        this.remainingQuantity += amount;
    }
}
