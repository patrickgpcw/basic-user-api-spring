package com.example.basicuserapispring.auth;

import com.example.basicuserapispring.user.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final Duration ttl;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtEncoder tokens,
                       @Value("${security.jwt.ttl}") Duration ttl) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.ttl = ttl;
        this.dummyHash = passwords.encode("invalid-credentials-dummy-password");
    }

    @Transactional
    public UserResponse register(CreateUserRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User(request.firstName().trim(), request.lastName().trim(), email,
                request.telephone(), passwords.encode(request.password()));
        return UserResponse.from(users.saveAndFlush(user));
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = users.findByEmail(normalize(request.email())).orElse(null);
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("basic-user-api")
                .subject(user.getId().toString()).issuedAt(now).expiresAt(now.plus(ttl)).build();
        String token = tokens.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", ttl.toSeconds());
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String subject) {
        return users.findById(Long.valueOf(subject)).map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists"));
    }

    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
}
