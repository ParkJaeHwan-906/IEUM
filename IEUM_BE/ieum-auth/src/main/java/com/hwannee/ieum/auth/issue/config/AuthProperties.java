package com.hwannee.ieum.auth.issue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "ieum.auth")
public record AuthProperties(
        String issuer,
        String privateKey,
        @DefaultValue List<String> previousKeys,
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("P14D") Duration refreshTokenTtl
) {
}
