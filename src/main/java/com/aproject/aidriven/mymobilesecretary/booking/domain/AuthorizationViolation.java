package com.aproject.aidriven.mymobilesecretary.booking.domain;

public final class AuthorizationViolation extends IllegalStateException {

    private final AuthorizationFailure failure;

    public AuthorizationViolation(AuthorizationFailure failure) {
        super(failure.name());
        this.failure = failure;
    }

    public AuthorizationFailure failure() {
        return failure;
    }
}
