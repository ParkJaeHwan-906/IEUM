package com.hwannee.ieum.stores.repository;

import com.hwannee.ieum.stores.domain.StoresItems;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoresItemsRepository extends JpaRepository<StoresItems, Long> {

    Optional<StoresItems> findByUid(String uid);

    List<StoresItems> findAllByStore_UidOrderByIdDesc(String storeUid);

    // 1단계(잠금 없음) 전용. JPQL 벌크 UPDATE 는 @Version 검사를 거치지 않으므로
    // "조회 → 계산 → 덮어쓰기" 사이의 갱신 유실(lost update)이 그대로 드러난다
    @Modifying
    @Query("update StoresItems s set s.remainingQuantity = :remaining where s.id = :id")
    int overwriteRemainingQuantity(@Param("id") Long id, @Param("remaining") int remaining);

    @Modifying
    @Query("""
        update StoresItems s
           set s.remainingQuantity = s.remainingQuantity - :quantity
         where s.id = :id
           and s.remainingQuantity >= :quantity
        """)
    int deductIfAvailable(@Param("id") Long id, @Param("quantity") int quantity);

    @Modifying
    @Query("""
        update StoresItems s
           set s.remainingQuantity = s.remainingQuantity + :quantity
         where s.id = :id
           and s.remainingQuantity + :quantity <= s.initialQuantity
        """)
    int restoreIfWithinInitial(@Param("id") Long id, @Param("quantity") int quantity);
}
