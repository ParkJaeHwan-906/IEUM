package com.hwannee.ieum.auth.issue.web.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code /auth/refresh} 와 {@code /auth/logout} 이 공용으로 쓴다. */
public record RefreshRequest(
        @NotBlank String refreshToken
) {
}
