package com.hwannee.ieum.common.web;

import com.hwannee.ieum.common.exception.ApiException;
import com.hwannee.ieum.orders.domain.InvalidOrderStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// TODO(3 공통): SecurityExceptionAdvice(401/403) 와 합칠지, Bean Validation 400 에 필드별 오류를 넣을지 결정
@RestControllerAdvice
public class ApiExceptionAdvice {

    @ExceptionHandler(ApiException.class)
    public ProblemDetail api(ApiException e) {
        return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ProblemDetail invalidTransition(InvalidOrderStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    // 2단계(optimistic)에서 재시도를 모두 소진했을 때 클라이언트가 받는 응답
    // TODO(2단계 재시도): 재시도 계층이 생기면 여기 도달하는 것은 "최종 실패"만이어야 한다
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail optimisticLockConflict(ObjectOptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "요청이 몰려 처리하지 못했습니다. 다시 시도해 주세요.");
    }
}
