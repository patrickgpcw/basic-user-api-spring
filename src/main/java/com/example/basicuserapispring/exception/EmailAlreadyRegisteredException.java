package com.example.basicuserapispring.exception;

import org.springframework.http.HttpStatus;

public class EmailAlreadyRegisteredException extends ApiException {
    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, "Email already registered", "EMAIL_ALREADY_REGISTERED");
    }
}
