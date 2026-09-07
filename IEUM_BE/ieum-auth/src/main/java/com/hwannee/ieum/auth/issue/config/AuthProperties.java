package com.hwannee.ieum.auth.issue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "ieum.auth")
public record AuthProperties(
        String issuer,
        String privateKey,
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("P14D") Duration refreshTokenTtl
) {
}
