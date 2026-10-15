package com.example.basicuserapispring;

import com.example.basicuserapispring.user.User;
import com.example.basicuserapispring.user.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtEncoder encoder;
    User owner;
    User other;
    String authorization;
    private static final String UPDATE = """
            {"firstName":" Updated ","lastName":" Name ","email":"NEW@example.com","telephone":"123"}
            """;

    @BeforeEach
    void setUp() {
        users.deleteAll();
        owner = users.saveAndFlush(new User("Owner", "User", "owner@example.com", "456", "original-hash"));
        other = users.saveAndFlush(new User("Other", "User", "other@example.com", null, "other-hash"));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("basic-user-api").subject(owner.getId().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        authorization = "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void updatesOnlyAuthenticatedUserAndPreservesPassword() throws Exception {
        mvc.perform(put("/api/users/me").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owner.getId()))
                .andExpect(jsonPath("$.firstName").value("Updated"))
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        User updated = users.findById(owner.getId()).orElseThrow();
        assertThat(updated.getPasswordHash()).isEqualTo("original-hash");
        assertThat(updated.getLastName()).isEqualTo("Name");
        assertThat(updated.getTelephone()).isEqualTo("123");
        assertThat(users.findById(other.getId()).orElseThrow().getEmail()).isEqualTo("other@example.com");
    }

    @Test
    void acceptsUnchangedEmailAndClearsOmittedTelephone() throws Exception {
        mvc.perform(put("/api/users/me").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Owner","lastName":"User","email":"OWNER@example.com"}
                                """))
                .andExpect(status().isOk());
        assertThat(users.findById(owner.getId()).orElseThrow().getTelephone()).isNull();
    }

    @Test
    void duplicateEmailReturnsCustomErrorWithoutChangingProfile() throws Exception {
        mvc.perform(put("/api/users/me").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE.replace("NEW@example.com", "OTHER@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        assertThat(users.findById(owner.getId()).orElseThrow().getFirstName()).isEqualTo("Owner");
    }

    @Test
    void invalidUpdateReturnsValidationErrorWithoutChangingProfile() throws Exception {
        mvc.perform(put("/api/users/me").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE.replace("NEW@example.com", "invalid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(users.findById(owner.getId()).orElseThrow().getEmail()).isEqualTo("owner@example.com");
    }

    @Test
    void deleteRemovesOnlyCurrentUserAndRejectsTheirExistingToken() throws Exception {
        mvc.perform(delete("/api/users/me").header("Authorization", authorization))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(users.existsById(owner.getId())).isFalse();
        assertThat(users.existsById(other.getId())).isTrue();
        mvc.perform(get("/api/users/me").header("Authorization", authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CURRENT_USER_UNAVAILABLE"));
        mvc.perform(delete("/api/users/me").header("Authorization", authorization))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/users/me").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousUsersCannotUpdateOrDelete() throws Exception {
        mvc.perform(put("/api/users/me").contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/me")).andExpect(status().isUnauthorized());
        assertThat(users.count()).isEqualTo(2);
    }

    @Test
    void cannotTargetAnotherAccountByPath() throws Exception {
        mvc.perform(put("/api/users/" + other.getId()).header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/users/" + other.getId()).header("Authorization", authorization))
                .andExpect(status().isNotFound());
        assertThat(users.findById(other.getId()).orElseThrow().getEmail()).isEqualTo("other@example.com");
    }

    @Test
    void bodyCannotOverrideIdentityOrPassword() throws Exception {
        String injected = UPDATE.trim().replace("}", ",\"id\":" + other.getId()
                + ",\"passwordHash\":\"injected\",\"password\":\"injected\"}");
        mvc.perform(put("/api/users/me").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(injected))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.getId()));
        assertThat(users.findById(owner.getId()).orElseThrow().getPasswordHash()).isEqualTo("original-hash");
        assertThat(users.findById(other.getId()).orElseThrow().getPasswordHash()).isEqualTo("other-hash");
    }
}
