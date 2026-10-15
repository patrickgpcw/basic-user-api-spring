package com.example.basicuserapispring.exception;

import org.springframework.http.HttpStatus;

public class InvalidRefreshTokenException extends ApiException {
    public InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or expired. Please log in again.",
                "INVALID_REFRESH_TOKEN");
    }
}
