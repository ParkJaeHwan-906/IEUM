package com.hwannee.ieum.auth.issue.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 필터 계층에서 난 401/403 을 MVC 예외 처리로 넘겨, 컨트롤러 예외와 같은
 * {@code ProblemDetail} 형식으로 응답한다. 실제 변환은 3.7 의 {@code AuthExceptionAdvice} 가 한다.
 */
@Component
public class ProblemDetailAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public ProblemDetailAuthHandlers(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) {
        resolver.resolveException(request, response, null, exception);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) {
        resolver.resolveException(request, response, null, exception);
    }
}
