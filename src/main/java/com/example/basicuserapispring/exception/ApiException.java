package com.example.basicuserapispring.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public abstract class ApiException extends ResponseStatusException {
    private final String code;
    protected ApiException(HttpStatus status, String message, String code) {
        super(status, message);
        this.code = code;
    }
    public String getCode() { return code; }
}
