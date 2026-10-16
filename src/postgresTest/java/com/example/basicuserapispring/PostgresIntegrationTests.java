package com.example.basicuserapispring;

import com.example.basicuserapispring.auth.*;
import com.example.basicuserapispring.exception.InvalidRefreshTokenException;
import com.example.basicuserapispring.user.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Testcontainers
class PostgresIntegrationTests {
    // Matches the application's Compose major version. No fixed port or shared volume.
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("basic_user_api_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired UserRepository users;
    @Autowired AuthSessionRepository sessions;
    @Autowired AuthSessionService sessionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void cleanDatabase() { users.deleteAll(); }

    @Test
    void runsProductionMigrationsAndValidatesSchemaOnRealPostgres() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).extracting(info -> info.getVersion().toString()).contains("1", "2");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void databaseEnforcesUniqueEmailsEvenWithoutServiceValidation() {
        users.saveAndFlush(user("duplicate@example.com"));
        assertThatThrownBy(() -> users.saveAndFlush(user("duplicate@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void databaseEnforcesForeignKeyAndDeletesSessionsInCascade() {
        User user = users.saveAndFlush(user("cascade@example.com"));
        sessionService.create(user.getId());
        sessionService.create(user.getId());
        assertThat(sessions.count()).isEqualTo(2);
        jdbc.update("delete from users where id = ?", user.getId());
        assertThat(sessions.count()).isZero();
        assertThatThrownBy(() -> sessions.saveAndFlush(new AuthSession(UUID.randomUUID(), user.getId(),
                "0".repeat(64), Instant.now().plusSeconds(3600))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsUuidAndTimestampWithTimeZone() {
        User user = users.saveAndFlush(user("timestamp@example.com"));
        UUID id = UUID.randomUUID();
        Instant expires = Instant.parse("2026-12-01T12:34:56.123456Z");
        sessions.saveAndFlush(new AuthSession(id, user.getId(), "a".repeat(64), expires));
        AuthSession loaded = sessions.findById(id).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getExpiresAt()).isEqualTo(expires);
        assertThat(loaded.isActive(expires.minus(1, ChronoUnit.MICROS))).isTrue();
        assertThat(loaded.isActive(expires)).isFalse();
    }

    @Test
    void supportsRegistrationLoginRefreshProfileAndLogoutOverHttp() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Postgres","lastName":"Test","email":"pg@example.com","password":"password123"}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        String login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"pg@example.com","password":"password123"}
                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var tokens = json.readTree(login);
        String refresh = tokens.get("refreshToken").asString();
        String renewed = mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("refreshToken", refresh))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String authorization = "Bearer " + json.readTree(renewed).get("accessToken").asString();
        mvc.perform(put("/api/users/me").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"firstName":"Updated","lastName":"Test","email":"pg@example.com"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Updated"));
        mvc.perform(post("/api/logout").header("Authorization", authorization)).andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").header("Authorization", authorization)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("refreshToken",
                                json.readTree(renewed).get("refreshToken").asString()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pessimisticLockPreventsConcurrentRefreshTokenReuse() throws Exception {
        User user = users.saveAndFlush(user("concurrent@example.com"));
        LoginResponse initial = sessionService.create(user.getId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> renew = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("Start barrier timed out");
            try {
                sessionService.refresh(initial.refreshToken());
                return 200;
            } catch (InvalidRefreshTokenException exception) {
                return 401;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(renew);
            Future<Integer> second = executor.submit(renew);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 401);
        }
    }

    private User user(String email) { return new User("Postgres", "Test", email, null, "unused-hash"); }
}
