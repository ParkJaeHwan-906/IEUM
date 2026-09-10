package com.hwannee.ieum.auth.issue.web.dto;

import com.hwannee.ieum.users.domain.UserType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Size(max = 10) String name,
        @NotBlank @Pattern(regexp = "^01[016789]\\d{7,8}$", message = "휴대전화 번호 형식이 아닙니다") String tel,
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank @Size(min = 2, max = 20) String nickname,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull UserType userType
) {
}
