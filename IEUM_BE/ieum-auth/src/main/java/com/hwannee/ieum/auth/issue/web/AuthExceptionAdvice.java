package com.hwannee.ieum.auth.issue.web;

import com.hwannee.ieum.auth.issue.exception.AuthException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionAdvice {

    @ExceptionHandler(AuthenticationException.class)                // 필터 계층 401
    public ProblemDetail unauthorized(AuthenticationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
    }

    @ExceptionHandler(AccessDeniedException.class)                  // 필터 계층 403
    public ProblemDetail forbidden(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");
    }

    @ExceptionHandler(AuthException.class)                          // 서비스 예외
    public ProblemDetail auth(AuthException e) {
        return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)        // unique 경합
    public ProblemDetail conflict(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "이미 사용 중인 정보입니다.");
    }
}
