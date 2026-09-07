package com.hwannee.ieum.users.repository;

import com.hwannee.ieum.users.domain.Users;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsersRepository extends JpaRepository<Users, Long> {
}
