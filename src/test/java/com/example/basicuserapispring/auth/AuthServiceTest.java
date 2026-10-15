package com.example.basicuserapispring.auth;

import com.example.basicuserapispring.user.CreateUserRequest;
import com.example.basicuserapispring.user.User;
import com.example.basicuserapispring.user.UserRepository;
import com.example.basicuserapispring.user.UserResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock UserRepository users;
    @Mock PasswordEncoder passwords;
    @Mock JwtEncoder tokens;
    private AuthService service;
    private static final Duration TTL = Duration.ofMinutes(15);

    @BeforeEach
    void setUp() {
        when(passwords.encode("invalid-credentials-dummy-password")).thenReturn("dummy-hash");
        service = new AuthService(users, passwords, tokens, TTL);
    }

    @Test
    void registrationNormalizesInputAndPersistsOnlyEncodedPassword() {
        var request = new CreateUserRequest(" Patrick ", " Pessanha ", " PATRICK@example.com ",
                "+5511999999999", "password123");
        when(passwords.encode("password123")).thenReturn("encoded-password");
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 42L);
            return user;
        });

        UserResponse response = service.register(request);

        var saved = ArgumentCaptor.forClass(User.class);
        verify(users).existsByEmail("patrick@example.com");
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("encoded-password");
        assertThat(response).isEqualTo(new UserResponse(42L, "Patrick", "Pessanha",
                "patrick@example.com", "+5511999999999"));
        verifyNoInteractions(tokens);
    }

    @Test
    void duplicateEmailDoesNotSaveUserOrEncodeTheirPassword() {
        when(users.existsByEmail("patrick@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new CreateUserRequest("Patrick", "Pessanha",
                "PATRICK@example.com", null, "password123")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).isEqualTo("Email already registered");
                });

        verify(users, never()).saveAndFlush(any());
        verify(passwords, never()).encode("password123");
        verifyNoInteractions(tokens);
    }

    @Test
    void loginIssuesTokenWithUserIdIssuerAlgorithmAndConfiguredLifetime() {
        when(users.findByEmail("patrick@example.com")).thenReturn(Optional.of(user()));
        when(passwords.matches("password123", "stored-hash")).thenReturn(true);
        when(tokens.encode(any())).thenReturn(Jwt.withTokenValue("signed-token")
                .header("alg", "HS256").subject("42").build());
        Instant before = Instant.now();

        LoginResponse response = service.login(new LoginRequest(" PATRICK@example.com ", "password123"));

        Instant after = Instant.now();
        var parameters = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(tokens).encode(parameters.capture());
        var claims = parameters.getValue().getClaims();
        assertThat(parameters.getValue().getJwsHeader().getAlgorithm()).isEqualTo(MacAlgorithm.HS256);
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.getClaimAsString("iss")).isEqualTo("basic-user-api");
        assertThat(claims.getIssuedAt()).isBetween(before, after);
        assertThat(claims.getExpiresAt()).isEqualTo(claims.getIssuedAt().plus(TTL));
        assertThat(claims.getClaims()).doesNotContainKeys("password", "passwordHash", "email");
        assertThat(response).isEqualTo(new LoginResponse("signed-token", "Bearer", 900));
    }

    @Test
    void wrongPasswordDoesNotIssueToken() {
        when(users.findByEmail("patrick@example.com")).thenReturn(Optional.of(user()));
        when(passwords.matches("wrong", "stored-hash")).thenReturn(false);

        assertUnauthorizedLogin("patrick@example.com");

        verify(passwords).matches("wrong", "stored-hash");
        verifyNoInteractions(tokens);
    }

    @Test
    void unknownEmailStillChecksDummyHashAndDoesNotIssueToken() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        when(passwords.matches("wrong", "dummy-hash")).thenReturn(false);

        assertUnauthorizedLogin("missing@example.com");

        verify(passwords).matches("wrong", "dummy-hash");
        verifyNoInteractions(tokens);
    }

    @Test
    void currentUserReturnsPublicProfile() {
        when(users.findById(42L)).thenReturn(Optional.of(user()));
        assertThat(service.currentUser("42")).isEqualTo(new UserResponse(42L, "Patrick", "Pessanha",
                "patrick@example.com", null));
    }

    @Test
    void deletedUserIsUnauthorized() {
        when(users.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.currentUser("42"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private void assertUnauthorizedLogin(String email) {
        assertThatThrownBy(() -> service.login(new LoginRequest(email, "wrong")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.getReason()).isEqualTo("Invalid email or password");
                });
    }

    private User user() {
        var user = new User("Patrick", "Pessanha", "patrick@example.com", null, "stored-hash");
        ReflectionTestUtils.setField(user, "id", 42L);
        return user;
    }
}
