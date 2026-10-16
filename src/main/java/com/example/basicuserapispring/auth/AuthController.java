package com.example.basicuserapispring.auth;

import com.example.basicuserapispring.user.*;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService auth;
    private final AuthSessionService sessions;
    private final LoginRateLimiter loginRateLimiter;
    public AuthController(AuthService auth, AuthSessionService sessions, LoginRateLimiter loginRateLimiter) {
        this.auth = auth;
        this.sessions = sessions;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token and obtain new tokens")
    public LoginResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return sessions.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> logout(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        sessions.logout(jwt.getClaimAsString("sid"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users")
    @Operation(summary = "Register a user")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.created(URI.create("/api/users/me")).body(auth.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and obtain an access token")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
            description = "Too many login attempts. Retry-After indicates the wait in seconds.")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        loginRateLimiter.checkAccount(request.email());
        return auth.login(request);
    }

    @GetMapping("/users/me")
    @Operation(summary = "Get the authenticated user", security = @SecurityRequirement(name = "bearerAuth"))
    public UserResponse currentUser(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return auth.currentUser(jwt.getSubject());
    }
}
