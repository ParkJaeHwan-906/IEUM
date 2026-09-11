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

    // TODO(2.1 선택 단계, 조건부 UPDATE): 2단계와 3단계 사이의 중간 데이터 포인트
    //   update StoresItems s set s.remainingQuantity = s.remainingQuantity - :qty
    //   where s.id = :id and s.remainingQuantity >= :qty
    //   반환값 0 이면 재고 부족. @Version 없이도 정합성이 맞고 재시도가 없다
}
