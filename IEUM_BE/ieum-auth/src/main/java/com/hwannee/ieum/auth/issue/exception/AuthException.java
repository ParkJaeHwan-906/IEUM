package com.hwannee.ieum.auth.issue.exception;

import org.springframework.http.HttpStatus;

public abstract class AuthException extends RuntimeException {

    private final HttpStatus status;

    protected AuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static final class InvalidCredentials extends AuthException {
        public InvalidCredentials() {
            super(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
    }

    public static final class InvalidRefreshToken extends AuthException {
        public InvalidRefreshToken() {
            super(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh token 입니다.");
        }
    }

    public static final class DuplicateAccount extends AuthException {
        public DuplicateAccount(String what) { super(HttpStatus.CONFLICT, "이미 사용 중인 " + what + " 입니다"); }
    }

    public static final class UnsupportedUserType extends AuthException {
        public UnsupportedUserType() { super(HttpStatus.BAD_REQUEST, "가입할 수 없는 사용자 유형입니다"); }
    }
}
