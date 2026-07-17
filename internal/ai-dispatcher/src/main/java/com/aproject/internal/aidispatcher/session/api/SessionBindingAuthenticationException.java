package com.aproject.internal.aidispatcher.session.api;

final class SessionBindingAuthenticationException extends RuntimeException {

    SessionBindingAuthenticationException() {
        super("Valid session binding administrator credentials are required");
    }
}
