package com.example.basicuserapispring;

import com.example.basicuserapispring.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    private final ObjectMapper json = new ObjectMapper();
    private static final String REGISTER = """
            {"firstName":"Patrick","lastName":"Pessanha","email":"Patrick@example.com",
             "telephone":"+5511999999999","password":"password123"}
            """;

    @BeforeEach void clean() { users.deleteAll(); }

    private void register() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(REGISTER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("patrick@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"PATRICK@example.com","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accessToken").asString();
    }

    @Test void registerLoginAndReadCurrentUser() throws Exception {
        register();
        String hash = users.findByEmail("patrick@example.com").orElseThrow().getPasswordHash();
        assertThat(hash).isNotEqualTo("password123");
        assertThat(passwords.matches("password123", hash)).isTrue();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Patrick"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test void rejectsDuplicateEmailRegardlessOfCase() throws Exception {
        register();
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER.replace("Patrick@example.com", "patrick@example.com")))
                .andExpect(status().isConflict());
    }

    @Test void rejectsInvalidFields() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER.replace("password123", "short")))
                .andExpect(status().isBadRequest());
        assertThat(users.count()).isZero();
    }

    @Test void rejectsInvalidCredentialsWithoutDisclosingWhetherUserExists() throws Exception {
        register();
        for (String email : new String[]{"patrick@example.com", "missing@example.com"}) {
            mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        }
    }

    @Test void rejectsMissingAndInvalidTokens() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test void rejectsExpiredTokens() throws Exception {
        var now = java.time.Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer("basic-user-api").subject("1").issuedAt(now.minusSeconds(7200))
                .expiresAt(now.minusSeconds(3600)).build();
        String token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
    @ParameterizedTest(name = "rejects invalid registration field: {0} = {1}")
    @MethodSource("invalidRegistrationFields")
    void rejectsInvalidRegistrationFields(String field, String value) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "firstName", "Patrick", "lastName", "Pessanha",
                "email", "patrick@example.com", "password", "password123"));
        body.put(field, value);
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]", org.hamcrest.Matchers.startsWith(field + ":")));
        assertThat(users.count()).isZero();
    }

    static Stream<Arguments> invalidRegistrationFields() {
        return Stream.of(
                Arguments.of("firstName", " "),
                Arguments.of("firstName", "a".repeat(101)),
                Arguments.of("lastName", null),
                Arguments.of("lastName", "a".repeat(101)),
                Arguments.of("email", "invalid-email"),
                Arguments.of("email", ""),
                Arguments.of("telephone", "1".repeat(31)),
                Arguments.of("password", null),
                Arguments.of("password", "       "),
                Arguments.of("password", "a".repeat(129)));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        assertThat(users.count()).isZero();
    }

    @Test
    void rejectsInvalidLoginFields() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletedUserCannotUsePreviouslyIssuedToken() throws Exception {
        register();
        String token = login();
        users.deleteAll();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentUserDoesNotReturnAnotherUsersProfile() throws Exception {
        register();
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER.replace("Patrick@example.com", "another@example.com")
                                .replace("\"Patrick\"", "\"Another\"")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("patrick@example.com"));
    }

    @Test
    void rejectsTokenWithWrongIssuer() throws Exception {
        var now = java.time.Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer("another-api").subject("1").issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        String token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() throws Exception {
        register();
        String[] parts = login().split("\\.");
        // Preserve valid claims but replace the signature with 32 zero bytes.
        String tampered = parts[0] + "." + parts[1] + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
}
