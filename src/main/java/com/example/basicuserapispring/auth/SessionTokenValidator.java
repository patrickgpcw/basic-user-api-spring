package com.example.basicuserapispring.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;

public class SessionTokenValidator implements OAuth2TokenValidator<Jwt> {
    private final AuthSessionRepository sessions;
    public SessionTokenValidator(AuthSessionRepository sessions) { this.sessions = sessions; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            Object sid = token.getClaims().get("sid");
            if (sid instanceof String value && sessions.findById(UUID.fromString(value))
                    .filter(session -> session.isActive(Instant.now()))
                    .filter(session -> session.getUserId().toString().equals(token.getSubject())).isPresent()) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException exception) {
            // Malformed session claims are authentication failures, never server errors.
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Session is invalid or expired", null));
    }
}
