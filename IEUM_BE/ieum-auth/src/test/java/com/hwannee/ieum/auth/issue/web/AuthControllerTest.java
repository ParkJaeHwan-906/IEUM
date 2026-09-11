package com.hwannee.ieum.auth.issue.web;

import com.hwannee.ieum.auth.issue.config.JwtKeyConfig;
import com.hwannee.ieum.auth.issue.config.SecurityConfig;
import com.hwannee.ieum.auth.issue.exception.AuthException;
import com.hwannee.ieum.auth.issue.jwt.JwkSetController;
import com.hwannee.ieum.auth.issue.service.AuthService;
import com.hwannee.ieum.auth.issue.service.SignupService;
import com.hwannee.ieum.auth.issue.web.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, JwkSetController.class})
@Import({SecurityConfig.class, CorsConfig.class, JwtKeyConfig.class,
        ProblemDetailAuthHandlers.class, AuthExceptionAdvice.class})
@TestPropertySource(properties = {"ieum.auth.private-key=", "ieum.auth.previous-keys="})
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SignupService signupService;

    @MockitoBean
    AuthService authService;

    @Test
    void jwksExposesPublicKeyOnly() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=600")))
                .andExpect(jsonPath("$.keys", hasSize(1)))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist());
    }

    @Test
    void loginReturnsTokenPair() throws Exception {
        given(authService.login("user@ieum.com", "password1"))
                .willReturn(TokenResponse.of("access", "refresh", Duration.ofMinutes(15)));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "user@ieum.com", "password": "password1"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void loginFailureIs401WithSingleMessage() throws Exception {
        given(authService.login(anyString(), anyString())).willThrow(new AuthException.InvalidCredentials());

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "user@ieum.com", "password": "wrong-password"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    void invalidLoginBodyIs400WithoutCallingService() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "not-an-email", "password": ""}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        then(authService).shouldHaveNoInteractions();
    }

    @Test
    void signupReturns201WithUidOnly() throws Exception {
        given(signupService.signup(any())).willReturn("uid-1");

        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "홍길동", "tel": "01012345678", "email": "user@ieum.com",
                         "nickname": "길동이", "password": "password1", "userType": "CONSUMER"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").value("uid-1"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void duplicateSignupIs409() throws Exception {
        given(signupService.signup(any())).willThrow(new AuthException.DuplicateAccount("이메일"));

        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "홍길동", "tel": "01012345678", "email": "user@ieum.com",
                         "nickname": "길동이", "password": "password1", "userType": "CONSUMER"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("이미 사용 중인 이메일 입니다"));
    }

    @Test
    void adminSignupIs400() throws Exception {
        given(signupService.signup(any())).willThrow(new AuthException.UnsupportedUserType());

        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "관리자", "tel": "01012345678", "email": "admin@ieum.com",
                         "nickname": "관리자", "password": "password1", "userType": "ADMIN"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("가입할 수 없는 사용자 유형입니다"));
    }

    @Test
    void refreshWithInvalidTokenIs401() throws Exception {
        given(authService.refresh("stale")).willThrow(new AuthException.InvalidRefreshToken());

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
                        {"refreshToken": "stale"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("유효하지 않은 refresh token 입니다."));
    }

    @Test
    void logoutAlwaysReturns204() throws Exception {
        mockMvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""
                        {"refreshToken": "whatever"}
                        """))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        then(authService).should().logout("whatever");
    }

    @Test
    void everythingElseIs401() throws Exception {
        mockMvc.perform(get("/auth/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("인증이 필요합니다."));

        mockMvc.perform(get("/admin/anything"))
                .andExpect(status().isUnauthorized());
    }
}
