package com.hwannee.ieum.auth.verify.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SecurityExceptionAdvice {

    @ExceptionHandler(AuthenticationException.class)
    public ErrorResponse unauthenticated(AuthenticationException e) {
        return ErrorResponse.builder(e, HttpStatus.UNAUTHORIZED, "유효한 토큰이 필요합니다.")
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ErrorResponse forbidden(AccessDeniedException e) {
        return ErrorResponse.builder(e, HttpStatus.FORBIDDEN, "접근 권한이 없습니다.").build();
    }
}
