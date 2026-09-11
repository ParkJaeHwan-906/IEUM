package com.hwannee.ieum.stores.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import com.hwannee.ieum.users.domain.UsersAccount;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Getter
@Table(name = "stores")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stores extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_account_id", nullable = false)
    private UsersAccount usersAccount;

    @Column(length = 36, nullable = false, unique = true)
    private String uid;

    @Column(length = 20, nullable = false)
    private String name;

    @Column(name = "logo_img_url", nullable = true)
    private String logoImgUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "store_type", length = 20, nullable = false)
    private StoreType storeType;

    @Column(name = "open_at")
    private LocalTime openAt;

    @Column(name = "close_at")
    private LocalTime closeAt;

    @Column(name = "shutdown_at")
    private LocalDateTime shutdownAt;

    public Stores(UsersAccount usersAccount, String uid, String name, StoreType storeType,
                  LocalTime openAt, LocalTime closeAt) {
        this.usersAccount = usersAccount;
        this.uid = uid;
        this.name = name;
        this.storeType = storeType;
        this.openAt = openAt;
        this.closeAt = closeAt;
    }

    public void changeLogoImgUrl(String logoImgUrl) {
        this.logoImgUrl = logoImgUrl;
    }

    public void shutdown() {
        this.shutdownAt = LocalDateTime.now();
    }

    public boolean isShutdown() {
        return shutdownAt != null;
    }
}
