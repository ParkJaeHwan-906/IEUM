package com.hwannee.ieum.reviews.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import com.hwannee.ieum.orders.domain.UsersOrders;
import com.hwannee.ieum.stores.domain.Stores;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "items_reviews", indexes = @Index(name = "idx_items_reviews_store_id", columnList = "store_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemsReviews extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_order_id", nullable = false, unique = true)
    private UsersOrders usersOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Stores store;

    @Column(name = "img_url", length = 500)
    private String imgUrl;

    @Column(length = 1000, nullable = false)
    private String content;

    @Column(nullable = false, check = @CheckConstraint(name = "chk_items_reviews_rating", constraint = "rating BETWEEN 1 AND 5"))
    private Integer rating;

    @Column(name = "blind_at")
    private LocalDateTime blindAt;

    public ItemsReviews(UsersOrders usersOrder, String imgUrl, String content, Integer rating) {
        validateRating(rating);
        this.usersOrder = usersOrder;
        this.store = usersOrder.getStoresItem().getStore();
        this.imgUrl = imgUrl;
        this.content = content;
        this.rating = rating;
    }

    private static void validateRating(Integer rating) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new IllegalArgumentException("평점은 1 이상 5 이하여야 합니다. 입력값: " + rating);
        }
    }

    public void blind() {
        this.blindAt = LocalDateTime.now();
    }

    public boolean isBlinded() {
        return blindAt != null;
    }
}
