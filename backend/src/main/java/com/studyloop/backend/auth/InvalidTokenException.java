package com.studyloop.backend.auth;

public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("Invalid or expired refresh token");
    }
}
