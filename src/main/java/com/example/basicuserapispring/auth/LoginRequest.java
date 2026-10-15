package com.example.basicuserapispring.auth;

import jakarta.validation.constraints.*;

public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(max = 128) String password) {
    @Override public String toString() { return "LoginRequest[REDACTED]"; }
}
