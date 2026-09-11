package com.hwannee.ieum.common.exception;

import org.springframework.http.HttpStatus;

// 도메인별 예외(OrderException, StoreException)의 공통 부모. ApiExceptionAdvice 가 ProblemDetail 로 바꾼다
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
