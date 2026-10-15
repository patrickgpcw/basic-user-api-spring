package com.example.basicuserapispring.auth;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthSessionServiceTest {
    @Mock AuthSessionRepository sessions;
    @Mock JwtEncoder encoder;

    @Test
    void issuesSignedAccessTokenBoundToSessionAndStoresOnlyRefreshHash() {
        when(encoder.encode(any())).thenReturn(Jwt.withTokenValue("signed-token")
                .header("alg", "HS256").subject("42").build());
        var service = new AuthSessionService(sessions, encoder, Duration.ofMinutes(15), Duration.ofDays(7));
        Instant before = Instant.now();

        LoginResponse response = service.create(42L);

        Instant after = Instant.now();
        var saved = ArgumentCaptor.forClass(AuthSession.class);
        verify(sessions).saveAndFlush(saved.capture());
        var parameters = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(parameters.capture());
        var claims = parameters.getValue().getClaims();
        assertThat(parameters.getValue().getJwsHeader().getAlgorithm()).isEqualTo(MacAlgorithm.HS256);
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.getClaimAsString("iss")).isEqualTo("basic-user-api");
        assertThat(claims.getClaimAsString("sid")).isEqualTo(saved.getValue().getId().toString());
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isBetween(before, after);
        assertThat(claims.getExpiresAt()).isEqualTo(claims.getIssuedAt().plusSeconds(900));
        assertThat(claims.getClaims()).doesNotContainKeys("password", "passwordHash", "refreshToken");
        assertThat(saved.getValue().getRefreshTokenHash()).isEqualTo(AuthSessionService.hash(response.refreshToken()));
        assertThat(response.refreshToken()).startsWith(saved.getValue().getId() + ".");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.refreshExpiresIn()).isEqualTo(604800);
        assertThat(response.toString()).doesNotContain(response.refreshToken(), "signed-token");
    }

    @Test
    void accessTokenCannotOutliveSession() {
        when(encoder.encode(any())).thenReturn(Jwt.withTokenValue("signed-token")
                .header("alg", "HS256").subject("42").build());
        var service = new AuthSessionService(sessions, encoder, Duration.ofHours(1), Duration.ofMinutes(5));
        LoginResponse response = service.create(42L);
        assertThat(response.expiresIn()).isEqualTo(300);
    }
}
