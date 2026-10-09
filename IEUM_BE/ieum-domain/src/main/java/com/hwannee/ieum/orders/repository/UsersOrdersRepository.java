package com.hwannee.ieum.orders.repository;

import com.hwannee.ieum.orders.domain.OrderState;
import com.hwannee.ieum.orders.domain.UsersOrders;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UsersOrdersRepository extends JpaRepository<UsersOrders, Long> {

    Optional<UsersOrders> findByIdAndUsersAccount_Uid(Long id, String uid);

    @Query("""
            select o from UsersOrders o
            where o.id = :id
              and o.storesItem.store.usersAccount.uid = :ownerUid
            """)
    Optional<UsersOrders> findByIdAndStoreOwnerUid(@Param("id") Long id, @Param("ownerUid") String ownerUid);

    List<UsersOrders> findAllByUsersAccount_UidOrderByIdDesc(String uid);

    List<UsersOrders> findAllByStoresItem_IdOrderByIdDesc(Long itemId);

    List<UsersOrders> findAllByStoresItem_IdAndOrderStateOrderByIdDesc(Long itemId, OrderState state);

    @Query("""
            select count(o) > 0 from UsersOrders o
            where o.storesItem.store.id = :storeId
              and o.orderState in :states
            """)
    boolean existsByStoreAndStates(@Param("storeId") Long storeId, @Param("states") Collection<OrderState> states);

    Optional<UsersOrders> findByUsersAccount_IdAndIdempotencyKey(Long accountId, String idempotencyKey);

    Optional<UsersOrders> findByUsersAccount_UidAndIdempotencyKey(String uid, String idempotencyKey);

    @Query("""
            select count(o) > 0 from UsersOrders o
            where o.usersAccount.id = :accountId
              and o.storesItem.id = :itemId
              and o.orderState in :states
            """)
    boolean existsByAccountAndItemInStates(@Param("accountId") Long accountId,
                                           @Param("itemId") Long itemId,
                                           @Param("states") Collection<OrderState> states);

    @Query("""
            select coalesce(sum(o.quantity), 0) from UsersOrders o
            where o.storesItem.id = :itemId
              and o.orderState in :states
            """)
    long sumQuantityByItemAndStates(@Param("itemId") Long itemId,
                                    @Param("states") Collection<OrderState> states);

    @Query("""
            select o.usersAccount.uid from UsersOrders o
            where o.storesItem.id = :itemId
              and o.orderState in :states
            """)
    List<String> findAccountUidsByItemAndStates(@Param("itemId") Long itemId,
                                                @Param("states") Collection<OrderState> states);

    @Query("""
            select o.id from UsersOrders o
            where o.orderState = com.hwannee.ieum.orders.domain.OrderState.PENDING
              and o.createdAt <= :threshold
            order by o.id
            """)
    List<Long> findPendingIdsCreatedBefore(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    @Query("""
            select o from UsersOrders o
            where o.orderState = com.hwannee.ieum.orders.domain.OrderState.READY_FOR_PICKUP
              and o.readyAt <= :threshold
            """)
    List<UsersOrders> findReadyForPickupBefore(@Param("threshold") LocalDateTime threshold);
}
