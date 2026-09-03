package com.hwannee.ieum.orders.repository;

import com.hwannee.ieum.orders.domain.UsersOrders;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsersOrdersRepository extends JpaRepository<UsersOrders, Long> {
}
