package com.example.basicuserapispring.auth;

import com.example.basicuserapispring.user.CreateUserRequest;
import com.example.basicuserapispring.user.User;
import com.example.basicuserapispring.user.UserRepository;
import com.example.basicuserapispring.user.UserResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    @Mock AuthSessionService sessions;
    private AuthService service;

    @BeforeEach
    void setUp() {
        when(passwords.encode("invalid-credentials-dummy-password")).thenReturn("dummy-hash");
        service = new AuthService(users, passwords, sessions);
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
        verifyNoInteractions(sessions);
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
        verifyNoInteractions(sessions);
    }

    @Test
    void validCredentialsCreateSessionForAuthenticatedUser() {
        when(users.findByEmail("patrick@example.com")).thenReturn(Optional.of(user()));
        when(passwords.matches("password123", "stored-hash")).thenReturn(true);
        var expected = new LoginResponse("access", "Bearer", 900, "refresh", 604800);
        when(sessions.create(42L)).thenReturn(expected);

        assertThat(service.login(new LoginRequest(" PATRICK@example.com ", "password123"))).isEqualTo(expected);
        verify(sessions).create(42L);
    }

    @Test
    void wrongPasswordDoesNotIssueToken() {
        when(users.findByEmail("patrick@example.com")).thenReturn(Optional.of(user()));
        when(passwords.matches("wrong", "stored-hash")).thenReturn(false);

        assertUnauthorizedLogin("patrick@example.com");

        verify(passwords).matches("wrong", "stored-hash");
        verifyNoInteractions(sessions);
    }

    @Test
    void unknownEmailStillChecksDummyHashAndDoesNotIssueToken() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        when(passwords.matches("wrong", "dummy-hash")).thenReturn(false);

        assertUnauthorizedLogin("missing@example.com");

        verify(passwords).matches("wrong", "dummy-hash");
        verifyNoInteractions(sessions);
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
