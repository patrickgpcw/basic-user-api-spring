package com.example.basicuserapispring.auth;

import com.example.basicuserapispring.exception.InvalidRefreshTokenException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionService {
    private final AuthSessionRepository sessions;
    private final JwtEncoder encoder;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public AuthSessionService(AuthSessionRepository sessions, JwtEncoder encoder,
            @Value("${security.jwt.ttl}") Duration accessTtl,
            @Value("${security.refresh.ttl}") Duration refreshTtl) {
        if (accessTtl.isNegative() || accessTtl.isZero() || refreshTtl.isNegative() || refreshTtl.isZero()) {
            throw new IllegalArgumentException("Token lifetimes must be positive");
        }
        this.sessions = sessions;
        this.encoder = encoder;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    @Transactional
    public LoginResponse create(Long userId) {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        String refreshToken = newRefreshToken(id);
        AuthSession session = new AuthSession(id, userId, hash(refreshToken), now.plus(refreshTtl));
        sessions.saveAndFlush(session);
        return issue(session, refreshToken, now);
    }

    @Transactional
    public LoginResponse refresh(String refreshToken) {
        UUID id;
        try {
            id = UUID.fromString(refreshToken.substring(0, refreshToken.indexOf('.')));
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new InvalidRefreshTokenException();
        }
        // Lock the stable session ID so simultaneous renewals cannot both consume the same token.
        AuthSession session = sessions.findLockedById(id).orElseThrow(InvalidRefreshTokenException::new);
        Instant now = Instant.now();
        if (!session.isActive(now) || !MessageDigest.isEqual(
                session.getRefreshTokenHash().getBytes(StandardCharsets.US_ASCII),
                hash(refreshToken).getBytes(StandardCharsets.US_ASCII))) {
            throw new InvalidRefreshTokenException();
        }
        String replacement = newRefreshToken(id);
        session.rotate(hash(replacement));
        sessions.flush();
        return issue(session, replacement, now);
    }

    @Transactional
    public void logout(String sessionId) {
        sessions.findLockedById(UUID.fromString(sessionId)).ifPresent(AuthSession::revoke);
    }

    private LoginResponse issue(AuthSession session, String refreshToken, Instant now) {
        Instant expiresAt = now.plus(accessTtl);
        if (expiresAt.isAfter(session.getExpiresAt())) expiresAt = session.getExpiresAt();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("basic-user-api")
                .subject(session.getUserId().toString()).claim("sid", session.getId().toString())
                .id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(expiresAt).build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(accessToken, "Bearer", Duration.between(now, expiresAt).toSeconds(),
                refreshToken, Duration.between(now, session.getExpiresAt()).toSeconds());
    }

    private String newRefreshToken(UUID sessionId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return sessionId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
