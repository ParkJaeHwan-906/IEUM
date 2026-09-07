package com.hwannee.ieum.users.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "users_account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsersAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private Users user;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", length = 20, nullable = false)
    private UserType userType;

    @Column(length = 36, nullable = false, unique = true)
    private String uid;

    @Column(length = 20, nullable = false, unique = true)
    private String nickname;

    @Column(length = 100, nullable = false)
    private String password;

    public UsersAccount(Users user, UserType userType, String uid, String nickname, String password) {
        this.user = user;
        this.userType = userType;
        this.uid = uid;
        this.nickname = nickname;
        this.password = password;
    }
}
