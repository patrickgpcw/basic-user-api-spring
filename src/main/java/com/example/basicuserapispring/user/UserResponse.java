package com.example.basicuserapispring.user;

public record UserResponse(Long id, String firstName, String lastName, String email, String telephone) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFirstName(), user.getLastName(),
                user.getEmail(), user.getTelephone());
    }
}
