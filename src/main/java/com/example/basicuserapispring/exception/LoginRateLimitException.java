package com.example.basicuserapispring.exception;

import org.springframework.http.HttpStatus;

public class LoginRateLimitException extends ApiException {
    private final long retryAfterSeconds;

    public LoginRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts. Please try again later.", "LOGIN_RATE_LIMITED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
