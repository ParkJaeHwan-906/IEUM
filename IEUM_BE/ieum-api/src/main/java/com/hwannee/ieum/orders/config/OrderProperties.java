package com.hwannee.ieum.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "ieum.order")
public record OrderProperties(
        @DefaultValue("PT15M") Duration pickupTtl
        // TODO(2.2 멱등성): idempotencyTtl (기본 P1D)
) {
}
