package com.studyloop.backend.course;

// Thrown when an email-targeted invite is redeemed by someone signed in with a different
// address → 403.
public class InviteEmailMismatchException extends RuntimeException {

    public InviteEmailMismatchException() {
        super("This invite was issued for a different email address.");
    }
}
