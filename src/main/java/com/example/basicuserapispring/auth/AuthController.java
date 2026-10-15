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
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/users")
    @Operation(summary = "Register a user")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.created(URI.create("/api/users/me")).body(auth.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and obtain an access token")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) { return auth.login(request); }

    @GetMapping("/users/me")
    @Operation(summary = "Get the authenticated user", security = @SecurityRequirement(name = "bearerAuth"))
    public UserResponse currentUser(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return auth.currentUser(jwt.getSubject());
    }
}
