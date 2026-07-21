package com.studyloop.backend.course;

// Thrown when an invite is found but its expiry has passed → 410 Gone.
public class InviteExpiredException extends RuntimeException {

    public InviteExpiredException() {
        super("This invite link has expired.");
    }
}
