package com.hwannee.ieum.auth.verify.config;

import com.hwannee.ieum.auth.verify.principal.CurrentUserArgumentResolver;
import com.hwannee.ieum.auth.verify.web.ProblemDetailAuthHandlers;
import com.hwannee.ieum.auth.verify.web.SecurityExceptionAdvice;
import com.hwannee.ieum.orders.service.OrderCreator;
import com.hwannee.ieum.orders.service.OrderService;
import com.hwannee.ieum.orders.web.OrderController;
import com.hwannee.ieum.orders.web.OwnerOrderController;
import com.hwannee.ieum.stores.service.StoreItemService;
import com.hwannee.ieum.stores.service.StoreService;
import com.hwannee.ieum.stores.web.OwnerStoreController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {OwnerStoreController.class, OwnerOrderController.class, OrderController.class})
@Import({SecurityConfig.class, CorsConfig.class, WebMvcSecurityConfig.class, CurrentUserArgumentResolver.class,
        ProblemDetailAuthHandlers.class, SecurityExceptionAdvice.class})
class RoleAccessTest {

    private static final String CONSUMER_TOKEN = "consumer-token";
    private static final String OWNER_TOKEN = "owner-token";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    StoreService storeService;

    @MockitoBean
    StoreItemService storeItemService;

    @MockitoBean
    OrderService orderService;

    @MockitoBean
    OrderCreator orderCreator;

    @BeforeEach
    void stubDecoder() {
        given(jwtDecoder.decode(CONSUMER_TOKEN)).willReturn(jwt("uid-consumer", "CONSUMER"));
        given(jwtDecoder.decode(OWNER_TOKEN)).willReturn(jwt("uid-owner", "BUSINESS_OWNER"));
    }

    @Test
    void 소비자_토큰으로_점주_주문_API_를_부르면_403() throws Exception {
        expectForbidden(post("/api/owner/orders/1/approve"), CONSUMER_TOKEN);
        then(orderService).should(never()).approve(any(), any());
    }

    @Test
    void 소비자_토큰으로_점주_가게_API_를_부르면_403() throws Exception {
        expectForbidden(get("/api/owner/stores/me"), CONSUMER_TOKEN);
        expectForbidden(post("/api/owner/stores/s-1/shutdown"), CONSUMER_TOKEN);
        expectForbidden(post("/api/owner/stores/s-1/items/i-1/close"), CONSUMER_TOKEN);
        expectForbidden(get("/api/owner/stores/s-1/items/i-1/orders"), CONSUMER_TOKEN);
        expectForbidden(patch("/api/owner/stores/s-1/items/i-1/quantity")
                .contentType(MediaType.APPLICATION_JSON).content("{\"initialQuantity\":1}"), CONSUMER_TOKEN);
        expectForbidden(patch("/api/owner/stores/s-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"openAt\":\"09:00\",\"closeAt\":\"21:00\"}"),
                CONSUMER_TOKEN);
        then(storeService).should(never()).findMine(any());
        then(storeService).should(never()).shutdown(any(), any());
        then(storeService).should(never()).changeBusinessHours(any(), any(), any());
        then(storeItemService).should(never()).adjustQuantity(any(), any(), any(), anyInt());
        then(storeItemService).should(never()).close(any(), any(), any());
        then(storeItemService).should(never()).findOrders(any(), any(), any(), any());
    }

    @Test
    void 점주_토큰으로_소비자_주문_API_를_부르면_403() throws Exception {
        expectForbidden(get("/api/orders/me"), OWNER_TOKEN);
        expectForbidden(post("/api/orders/1/cancel"), OWNER_TOKEN);
        then(orderService).should(never()).cancel(any(), any());
    }

    @Test
    void 역할이_맞으면_통과한다() throws Exception {
        mockMvc.perform(get("/api/owner/stores/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + OWNER_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/orders/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + CONSUMER_TOKEN))
                .andExpect(status().isOk());
    }

    private void expectForbidden(MockHttpServletRequestBuilder request, String token) throws Exception {
        mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private static Jwt jwt(String uid, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(uid)
                .claim(SecurityConfig.CLAIM_ROLE, role)
                .claim(SecurityConfig.CLAIM_NICKNAME, "nick")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
    }
}
