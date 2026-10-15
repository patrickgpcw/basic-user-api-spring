package com.example.basicuserapispring.auth;

import com.example.basicuserapispring.user.*;
import java.util.Locale;
import com.example.basicuserapispring.exception.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuthSessionService sessions;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwords, AuthSessionService sessions) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.dummyHash = passwords.encode("invalid-credentials-dummy-password");
    }

    @Transactional
    public UserResponse register(CreateUserRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        User user = new User(request.firstName().trim(), request.lastName().trim(), email,
                request.telephone(), passwords.encode(request.password()));
        return UserResponse.from(users.saveAndFlush(user));
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = users.findByEmail(normalize(request.email())).orElse(null);
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null) {
            throw new InvalidCredentialsException();
        }
        return sessions.create(user.getId());
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String subject) {
        return users.findById(Long.valueOf(subject)).map(UserResponse::from)
                .orElseThrow(() -> new CurrentUserUnavailableException());
    }

    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
}
