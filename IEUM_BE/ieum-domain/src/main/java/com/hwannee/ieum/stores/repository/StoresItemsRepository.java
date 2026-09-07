package com.hwannee.ieum.stores.repository;

import com.hwannee.ieum.stores.domain.StoresItems;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoresItemsRepository extends JpaRepository<StoresItems, Long> {
}
