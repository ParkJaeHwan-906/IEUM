package com.hwannee.ieum.orders.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class PickupCodeIssuer {

    private final SecureRandom random = new SecureRandom();

    public String issue() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
