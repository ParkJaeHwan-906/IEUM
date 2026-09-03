package com.hwannee.ieum.registration.repository;

import com.hwannee.ieum.registration.domain.BusinessRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRegistrationRepository extends JpaRepository<BusinessRegistration, Long> {
}
