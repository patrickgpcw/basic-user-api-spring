package com.example.basicuserapispring;

import com.example.basicuserapispring.auth.*;
import com.example.basicuserapispring.exception.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"security.login.ip-limit=3", "security.login.account-limit=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginRateLimitIntegrationTests {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;

    @BeforeEach
    void rejectCredentials() { when(auth.login(any())).thenThrow(new InvalidCredentialsException()); }

    @Test
    void accountLimitAppliesAcrossIpsAndBeforePasswordVerification() throws Exception {
        mvc.perform(login("account@example.com", "10.0.1.1")).andExpect(status().isUnauthorized());
        mvc.perform(login("ACCOUNT@example.com", "10.0.1.2")).andExpect(status().isUnauthorized());
        mvc.perform(login("account@example.com", "10.0.1.3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
        verify(auth, times(2)).login(any());
    }

    @Test
    void ipLimitAppliesAcrossAccountsAndIgnoresForwardingHeaders() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(login("ip" + i + "@example.com", "10.0.2.1")
                            .header("X-Forwarded-For", "192.0.2." + i))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(login("ip-next@example.com", "10.0.2.1")
                        .header("X-Forwarded-For", "192.0.2.100")
                        .header("Forwarded", "for=192.0.2.101"))
                .andExpect(status().isTooManyRequests());
        verify(auth, times(3)).login(any());
        mvc.perform(login("ip-other@example.com", "10.0.2.2")).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedBodiesStillConsumeIpBudget() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/login").with(request -> { request.setRemoteAddr("10.0.3.1"); return request; })
                            .contentType(MediaType.APPLICATION_JSON).content("{"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(login("malformed@example.com", "10.0.3.1")).andExpect(status().isTooManyRequests());
        verifyNoInteractions(auth);
    }

    @Test
    void successfulLoginsDoNotResetBudget() throws Exception {
        doReturn(new LoginResponse("access", "Bearer", 3600, "refresh", 604800)).when(auth).login(any());
        for (int i = 0; i < 2; i++) {
            mvc.perform(login("success@example.com", "10.0.4.1")).andExpect(status().isOk());
        }
        mvc.perform(login("success@example.com", "10.0.4.1")).andExpect(status().isTooManyRequests());
        verify(auth, times(2)).login(any());
    }

    @Test
    void loginLimitDoesNotBlockHealthOrDocumentation() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(login("health" + i + "@example.com", "10.0.5.1")).andExpect(status().isUnauthorized());
        }
        mvc.perform(login("health-next@example.com", "10.0.5.1")).andExpect(status().isTooManyRequests());
        mvc.perform(get("/actuator/health").with(request -> { request.setRemoteAddr("10.0.5.1"); return request; }))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs").with(request -> { request.setRemoteAddr("10.0.5.1"); return request; }))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder login(String email, String ip) {
        return post("/api/login").with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}");
    }
}
