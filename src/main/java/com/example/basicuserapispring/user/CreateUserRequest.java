package com.example.basicuserapispring.user;

import jakarta.validation.constraints.*;

public record CreateUserRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 30) String telephone,
        @NotBlank @Size(min = 8, max = 128) String password) {
    @Override public String toString() { return "CreateUserRequest[REDACTED]"; }
}
