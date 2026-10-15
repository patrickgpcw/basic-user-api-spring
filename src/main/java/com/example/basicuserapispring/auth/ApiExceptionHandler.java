package com.example.basicuserapispring.auth;

import com.example.basicuserapispring.exception.ApiException;
import com.example.basicuserapispring.exception.LoginRateLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(LoginRateLimitException.class)
    ResponseEntity<ProblemDetail> loginRateLimit(LoginRateLimitException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
        problem.setProperty("code", exception.getCode());
        problem.setProperty("retryAfterSeconds", exception.getRetryAfterSeconds());
        return ResponseEntity.status(exception.getStatusCode())
                .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail business(ApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
        problem.setProperty("code", exception.getCode());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields");
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
        return problem;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException exception) {
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "User conflicts with an existing record");
        problem.setProperty("code", "USER_CONFLICT");
        return problem;
    }
}
