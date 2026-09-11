package com.hwannee.ieum.orders.repository;

import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UsersOrdersRepository extends JpaRepository<UsersOrders, Long> {

    Optional<UsersOrders> findByIdAndUsersAccount_Uid(Long id, String uid);

    List<UsersOrders> findAllByUsersAccount_UidOrderByIdDesc(String uid);

    // TODO(2.2 중복 예약): OrderService.create 에서 호출. 동시 요청 사이의 틈은 3단계 Lua 스크립트에서 닫는다
    @Query("""
            select count(o) > 0 from UsersOrders o
            where o.usersAccount.id = :accountId
              and o.storesItem.id = :itemId
              and o.orderState in :states
            """)
    boolean existsByAccountAndItemInStates(@Param("accountId") Long accountId,
                                           @Param("itemId") Long itemId,
                                           @Param("states") Collection<OrderState> states);

    // TODO(2.2 Reconciliation): Sorted Set 에서 누락된 만료 후보를 DB 기준으로 다시 찾는 용도
    @Query("""
            select o from UsersOrders o
            where o.orderState = com.hwannee.ieum.orders.domain.OrderState.READY_FOR_PICKUP
              and o.readyAt <= :threshold
            """)
    List<UsersOrders> findReadyForPickupBefore(@Param("threshold") LocalDateTime threshold);
}
