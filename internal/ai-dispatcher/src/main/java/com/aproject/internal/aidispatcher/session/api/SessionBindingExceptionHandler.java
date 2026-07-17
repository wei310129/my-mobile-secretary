package com.aproject.internal.aidispatcher.session.api;

import com.aproject.internal.aidispatcher.session.SessionBindingConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SessionBindingController.class)
class SessionBindingExceptionHandler {

    @ExceptionHandler(SessionBindingAuthenticationException.class)
    ResponseEntity<ApiError> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer")
                .body(new ApiError("UNAUTHORIZED", "Valid administrator credentials are required"));
    }

    @ExceptionHandler({SessionBindingConflictException.class, DataIntegrityViolationException.class})
    ResponseEntity<ApiError> conflict(RuntimeException exception) {
        String message = exception instanceof SessionBindingConflictException
                ? exception.getMessage()
                : "The requested technical session id is already bound";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("SESSION_BINDING_CONFLICT", message));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> badRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_SESSION_BINDING_REQUEST", exception.getMessage()));
    }

    record ApiError(String code, String message) {
    }
}
