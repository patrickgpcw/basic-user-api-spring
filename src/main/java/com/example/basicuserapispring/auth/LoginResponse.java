package com.example.basicuserapispring.auth;

public record LoginResponse(String accessToken, String tokenType, long expiresIn,
                            String refreshToken, long refreshExpiresIn) {
    @Override public String toString() { return "LoginResponse[REDACTED]"; }
}
