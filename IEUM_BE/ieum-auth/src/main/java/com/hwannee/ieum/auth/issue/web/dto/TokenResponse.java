package com.hwannee.ieum.auth.issue.web.dto;

import java.time.Duration;

/**
 * @param expiresIn access token 남은 수명(초). OAuth2 토큰 응답 관례를 따른다.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
    public static TokenResponse of(String accessToken, String refreshToken, Duration accessTokenTtl) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", accessTokenTtl.toSeconds());
    }
}
