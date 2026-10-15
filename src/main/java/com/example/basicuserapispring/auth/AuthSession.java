package com.example.basicuserapispring.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions")
public class AuthSession {
    @Id private UUID id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 64) private String refreshTokenHash;
    @Column(nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean revoked;

    protected AuthSession() {}
    public AuthSession(UUID id, Long userId, String refreshTokenHash, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.refreshTokenHash = refreshTokenHash;
        this.expiresAt = expiresAt;
    }
    public UUID getId() { return id; }
    public Long getUserId() { return userId; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive(Instant now) { return !revoked && expiresAt.isAfter(now); }
    public void rotate(String hash) { refreshTokenHash = hash; }
    public void revoke() { revoked = true; }
}
