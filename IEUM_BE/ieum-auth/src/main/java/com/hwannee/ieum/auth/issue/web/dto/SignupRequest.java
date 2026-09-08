package com.hwannee.ieum.auth.issue.web.dto;

import com.hwannee.ieum.users.domain.UserType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 길이 제한은 {@code Users} / {@code UsersAccount} 컬럼 길이에 맞춘다.
 * 컬럼보다 길게 허용하면 DB 에서 500 이 나므로 여기서 400 으로 막는다.
 */
public record SignupRequest(
        @NotBlank @Size(max = 10) String name,
        @NotBlank @Pattern(regexp = "^01[016789]\\d{7,8}$", message = "휴대전화 번호 형식이 아닙니다") String tel,
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank @Size(min = 2, max = 20) String nickname,
        // BCrypt 는 72바이트까지만 본다
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull UserType userType
) {
}
