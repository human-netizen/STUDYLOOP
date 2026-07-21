package com.studyloop.backend.course;

// Thrown when a token matches no live invite (unknown, or already revoked/spent) → 404.
public class InviteNotFoundException extends RuntimeException {

    public InviteNotFoundException() {
        super("This invite link is invalid or no longer active.");
    }
}
