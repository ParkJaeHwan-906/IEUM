package com.hwannee.ieum.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "ieum.order")
public record OrderProperties(
        @DefaultValue("PT15M") Duration pickupTtl,
        @DefaultValue Retry retry
) {
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("PT0.01S") Duration backoff
    ) {
    }
}
