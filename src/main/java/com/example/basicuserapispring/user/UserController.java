package com.example.basicuserapispring.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
@SecurityRequirement(name = "bearerAuth")
public class UserController {
    private final UserService users;
    public UserController(UserService users) { this.users = users; }

    @PutMapping
    @Operation(summary = "Replace the authenticated user's profile",
            description = "Updates name, email and telephone. Password is not changed. Omitted telephone is cleared.")
    public UserResponse update(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody UpdateUserRequest request) {
        return users.updateCurrentUser(jwt.getSubject(), request);
    }

    @DeleteMapping
    @Operation(summary = "Delete the authenticated user's account")
    public ResponseEntity<Void> delete(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        users.deleteCurrentUser(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
