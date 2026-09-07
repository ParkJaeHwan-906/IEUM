package com.hwannee.ieum.stores.repository;

import com.hwannee.ieum.stores.domain.Stores;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoresRepository extends JpaRepository<Stores, Long> {
}
