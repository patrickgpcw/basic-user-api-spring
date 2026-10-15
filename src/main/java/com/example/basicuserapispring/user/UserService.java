package com.example.basicuserapispring.user;

import com.example.basicuserapispring.exception.CurrentUserUnavailableException;
import com.example.basicuserapispring.exception.EmailAlreadyRegisteredException;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) { this.users = users; }

    @Transactional
    public UserResponse updateCurrentUser(String subject, UpdateUserRequest request) {
        User user = requireCurrentUser(subject);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (!user.getEmail().equals(email) && users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        user.updateProfile(request.firstName().trim(), request.lastName().trim(), email, request.telephone());
        return UserResponse.from(users.saveAndFlush(user));
    }

    @Transactional
    public void deleteCurrentUser(String subject) {
        users.delete(requireCurrentUser(subject));
        users.flush();
    }

    private User requireCurrentUser(String subject) {
        try {
            return users.findById(Long.valueOf(subject)).orElseThrow(CurrentUserUnavailableException::new);
        } catch (NumberFormatException exception) {
            throw new CurrentUserUnavailableException();
        }
    }
}
