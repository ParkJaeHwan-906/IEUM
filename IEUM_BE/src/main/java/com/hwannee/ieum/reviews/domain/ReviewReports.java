package com.hwannee.ieum.reviews.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import com.hwannee.ieum.users.domain.UsersAccount;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "review_reports")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewReports extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_review_id", nullable = false)
    private ItemsReviews itemsReview;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private UsersAccount reporter;

    @Column(length = 500, nullable = false)
    private String reason;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    public ReviewReports(ItemsReviews itemsReview, UsersAccount reporter, String reason) {
        this.itemsReview = itemsReview;
        this.reporter = reporter;
        this.reason = reason;
    }

    public void process() {
        this.processedAt = LocalDateTime.now();
    }

    public boolean isProcessed() {
        return processedAt != null;
    }
}
