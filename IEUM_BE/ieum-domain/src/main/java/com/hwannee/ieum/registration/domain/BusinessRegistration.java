package com.hwannee.ieum.registration.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import com.hwannee.ieum.stores.domain.Stores;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "business_registration")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusinessRegistration extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Stores store;

    @Column(name = "registration_img_url", length = 500, nullable = false)
    private String registrationImgUrl;

    @Column(name = "approve_at")
    private LocalDateTime approveAt;

    @Column(name = "disapprove_at")
    private LocalDateTime disapproveAt;

    public BusinessRegistration(Stores store, String registrationImgUrl) {
        this.store = store;
        this.registrationImgUrl = registrationImgUrl;
    }

    public void approve() {
        this.approveAt = LocalDateTime.now();
    }

    public void disapprove() {
        this.disapproveAt = LocalDateTime.now();
    }
}
