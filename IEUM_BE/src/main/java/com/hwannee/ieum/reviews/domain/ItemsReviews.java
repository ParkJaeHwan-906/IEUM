package com.hwannee.ieum.reviews.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import com.hwannee.ieum.orders.domain.UsersOrders;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "items_reviews")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemsReviews extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_order_id", nullable = false, unique = true)
    private UsersOrders usersOrder;

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
