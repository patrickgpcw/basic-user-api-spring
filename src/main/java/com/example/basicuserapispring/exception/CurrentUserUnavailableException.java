package com.example.basicuserapispring.exception;

import org.springframework.http.HttpStatus;

public class CurrentUserUnavailableException extends ApiException {
    public CurrentUserUnavailableException() {
        super(HttpStatus.UNAUTHORIZED, "User no longer exists", "CURRENT_USER_UNAVAILABLE");
    }
}
