package com.hwannee.ieum.auth.issue.web;

import com.hwannee.ieum.auth.issue.service.AuthService;
import com.hwannee.ieum.auth.issue.service.SignupService;
import com.hwannee.ieum.auth.issue.web.dto.LoginRequest;
import com.hwannee.ieum.auth.issue.web.dto.RefreshRequest;
import com.hwannee.ieum.auth.issue.web.dto.SignupRequest;
import com.hwannee.ieum.auth.issue.web.dto.SignupResponse;
import com.hwannee.ieum.auth.issue.web.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SignupService signupService;
    private final AuthService authService;

    public AuthController(SignupService signupService, AuthService authService) {
        this.signupService = signupService;
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return new SignupResponse(signupService.signup(request));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }
}
