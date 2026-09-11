package com.hwannee.ieum.stores.repository;

import com.hwannee.ieum.stores.domain.Stores;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoresRepository extends JpaRepository<Stores, Long> {

    Optional<Stores> findByUid(String uid);

    List<Stores> findAllByUsersAccount_UidOrderByIdDesc(String ownerUid);

    // TODO(정책): 점주 1명당 가게 수 제한 여부. 제한하면 existsByUsersAccount_Id 로 검사
    // TODO(README Customer): 지역별 판매 중 상품 조회 — Stores 에 위치(주소·좌표) 컬럼이 없어 설계 필요
}
