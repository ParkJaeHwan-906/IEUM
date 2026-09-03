package com.hwannee.ieum.users.domain;

import com.hwannee.ieum.common.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Users extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 10, nullable = false)
    private String name;

    @Column(length = 11, nullable = false, unique = true)
    private String tel;

    @Column(length = 100, nullable = false, unique = true)
    private String email;

    public Users(String name, String tel, String email) {
        this.name = name;
        this.tel = tel;
        this.email = email;
    }
}
