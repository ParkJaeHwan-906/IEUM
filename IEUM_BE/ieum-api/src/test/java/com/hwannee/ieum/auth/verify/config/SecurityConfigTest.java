package com.hwannee.ieum.auth.verify.config;

import com.hwannee.ieum.auth.verify.principal.CurrentUserArgumentResolver;
import com.hwannee.ieum.auth.verify.web.ProblemDetailAuthHandlers;
import com.hwannee.ieum.auth.verify.web.SecurityExceptionAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProbeController.class)
@Import({SecurityConfig.class, CorsConfig.class, WebMvcSecurityConfig.class, CurrentUserArgumentResolver.class,
        ProblemDetailAuthHandlers.class, SecurityExceptionAdvice.class})
class SecurityConfigTest {

    private static final String CONSUMER_TOKEN = "consumer-token";
    private static final String OWNER_TOKEN = "owner-token";
    private static final String BAD_TOKEN = "bad-token";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void stubDecoder() {
        given(jwtDecoder.decode(CONSUMER_TOKEN)).willReturn(jwt("uid-consumer", "CONSUMER", "소비자"));
        given(jwtDecoder.decode(OWNER_TOKEN)).willReturn(jwt("uid-owner", "BUSINESS_OWNER", "점주"));
        given(jwtDecoder.decode(BAD_TOKEN)).willThrow(new BadJwtException("서명 불일치"));
    }

    @Test
    void permitAllPathIsOpenWithoutToken() throws Exception {
        mockMvc.perform(get("/api/items/1"))
                .andExpect(status().isOk())
                .andExpect(content().string("item"));
    }

    @Test
    void missingTokenIs401ProblemDetailWithBearerChallenge() throws Exception {
        mockMvc.perform(get("/probe/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("유효한 토큰이 필요합니다."));
    }

    @Test
    void unknownPathIs401ByDefaultDeny() throws Exception {
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void invalidTokenIs401WithoutLeakingReason() throws Exception {
        mockMvc.perform(get("/probe/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + BAD_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.detail").value("유효한 토큰이 필요합니다."))
                .andExpect(content().string(not(containsString("서명"))));
    }

    @Test
    void validTokenResolvesCurrentUser() throws Exception {
        mockMvc.perform(get("/probe/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + CONSUMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-consumer"))
                .andExpect(jsonPath("$.role").value("CONSUMER"))
                .andExpect(jsonPath("$.nickname").value("소비자"));
    }

    @Test
    void wrongRoleIs403ProblemDetail() throws Exception {
        mockMvc.perform(get("/probe/owner").header(HttpHeaders.AUTHORIZATION, "Bearer " + CONSUMER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("접근 권한이 없습니다."));
    }

    @Test
    void matchingRoleIs200() throws Exception {
        mockMvc.perform(get("/probe/owner").header(HttpHeaders.AUTHORIZATION, "Bearer " + OWNER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string("uid-owner"));
    }

    @Test
    void currentUserOnPermitAllPathWithoutTokenIs401() throws Exception {
        mockMvc.perform(get("/api/items/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static Jwt jwt(String uid, String role, String nickname) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(uid)
                .claim(SecurityConfig.CLAIM_ROLE, role)
                .claim(SecurityConfig.CLAIM_NICKNAME, nickname)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
    }
}
