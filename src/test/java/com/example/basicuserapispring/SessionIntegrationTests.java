package com.example.basicuserapispring;

import com.example.basicuserapispring.auth.*;
import com.example.basicuserapispring.user.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthSessionRepository sessions;
    @Autowired AuthSessionService service;
    @Autowired JwtEncoder encoder;
    private final ObjectMapper json = new ObjectMapper();
    private User user;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        user = users.saveAndFlush(new User("Session", "User", "session@example.com", null, "unused"));
    }

    @Test
    void rotatesRefreshTokenAndStoresOnlyHash() throws Exception {
        LoginResponse initial = service.create(user.getId());
        AuthSession session = sessions.findAll().getFirst();
        assertThat(session.getRefreshTokenHash()).hasSize(64).isNotEqualTo(initial.refreshToken());
        var response = mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(body(initial.refreshToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var next = json.readTree(response);
        String refresh = next.get("refreshToken").asString();
        assertThat(refresh).isNotEqualTo(initial.refreshToken());
        assertThat(next.get("accessToken").asString()).isNotEqualTo(initial.accessToken());
        assertThat(sessions.findById(session.getId()).orElseThrow().getExpiresAt()).isEqualTo(session.getExpiresAt());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + next.get("accessToken").asString()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON).content(body(initial.refreshToken())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON).content(body(refresh)))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesAllTokensOfCurrentSessionButNotOtherSessions() throws Exception {
        LoginResponse initial = service.create(user.getId());
        LoginResponse rotated = service.refresh(initial.refreshToken());
        LoginResponse independent = service.create(user.getId());
        mvc.perform(post("/api/logout").header("Authorization", "Bearer " + rotated.accessToken()))
                .andExpect(status().isNoContent());
        for (String access : new String[]{initial.accessToken(), rotated.accessToken()}) {
            mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON).content(body(rotated.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + independent.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void expiredSessionRejectsBothTokenTypes() throws Exception {
        LoginResponse tokens = service.create(user.getId());
        AuthSession session = sessions.findAll().getFirst();
        org.springframework.test.util.ReflectionTestUtils.setField(session, "expiresAt", Instant.now().minusSeconds(1));
        sessions.saveAndFlush(session);
        mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON).content(body(tokens.refreshToken())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAccessTokenCanBeRenewedWithValidRefreshToken() throws Exception {
        LoginResponse tokens = service.create(user.getId());
        AuthSession session = sessions.findAll().getFirst();
        Instant now = Instant.now();
        String expired = signed(user.getId().toString(), session.getId().toString(), now.minusSeconds(3600));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON).content(body(tokens.refreshToken())))
                .andExpect(status().isOk());
    }

    @Test
    void deletingUserRemovesSessionsAndPreventsRefresh() throws Exception {
        LoginResponse tokens = service.create(user.getId());
        mvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());
        assertThat(sessions.count()).isZero();
        mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON).content(body(tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMalformedUnknownAndTamperedRefreshTokens() throws Exception {
        LoginResponse tokens = service.create(user.getId());
        for (String token : new String[]{"malformed", UUID.randomUUID() + ".unknown", tokens.refreshToken() + "x"}) {
            mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON).content(body(token)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        }
        mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMissingSessionAndSessionBelongingToAnotherUser() throws Exception {
        service.create(user.getId());
        String sid = sessions.findAll().getFirst().getId().toString();
        for (String token : new String[]{signed("99999", sid, Instant.now().plusSeconds(3600)),
                signed(user.getId().toString(), UUID.randomUUID().toString(), Instant.now().plusSeconds(3600))}) {
            mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void concurrentRefreshConsumesTokenOnlyOnce() throws Exception {
        LoginResponse initial = service.create(user.getId());
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> refresh = () -> {
            start.await();
            return mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON)
                    .content(body(initial.refreshToken()))).andReturn().getResponse().getStatus();
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(refresh);
            Future<Integer> second = executor.submit(refresh);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 401);
        }
    }

    private String body(String token) { return "{\"refreshToken\":\"" + token + "\"}"; }

    private String signed(String subject, String sid, Instant expiresAt) {
        var claims = JwtClaimsSet.builder().issuer("basic-user-api").subject(subject).claim("sid", sid)
                .issuedAt(Instant.now().minusSeconds(7200)).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
