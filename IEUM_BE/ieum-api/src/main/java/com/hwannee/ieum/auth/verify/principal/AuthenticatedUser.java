package com.hwannee.ieum.auth.verify.principal;

import com.hwannee.ieum.auth.verify.config.SecurityConfig;
import com.hwannee.ieum.users.domain.UserType;
import org.springframework.security.oauth2.jwt.Jwt;

public record AuthenticatedUser(String uid, UserType role, String nickname) {

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                jwt.getSubject(),
                UserType.valueOf(jwt.getClaimAsString(SecurityConfig.CLAIM_ROLE)),
                jwt.getClaimAsString(SecurityConfig.CLAIM_NICKNAME)
        );
    }
}
