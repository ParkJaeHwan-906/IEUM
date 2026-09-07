package com.hwannee.ieum.users.repository;

import com.hwannee.ieum.users.domain.UsersAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UsersAccountRepository extends JpaRepository<UsersAccount, Long> {

    @Query("select a from UsersAccount a join a.user u where u.email = :email")
    Optional<UsersAccount> findByEmail(@Param("email") String email);

    Optional<UsersAccount> findByUid(String uid);

    boolean existsByNickname(String nickname);
}
