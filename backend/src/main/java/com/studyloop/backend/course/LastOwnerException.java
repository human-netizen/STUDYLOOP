package com.studyloop.backend.course;

import java.util.UUID;

// The last OWNER of a course tried to leave it, be removed from it, or delete their account → 409.
//
// **This is a check with a test, not a constraint, and it could not be either of the other two.**
// A course with no owner is unadministrable: nobody can rename it, upload to it, remove anybody
// from it, or archive it, and every one of those failures is a 403 that reads like a bug. No
// foreign key expresses "at least one row with role = OWNER must remain", and no cascade would
// notice the last one going. The refusal names the course rather than the rule, because the fix
// is an action — promote somebody else first.
public class LastOwnerException extends RuntimeException {

    public LastOwnerException(UUID courseId) {
        super("This is the only owner of course " + courseId
                + ". Make somebody else an owner first.");
    }

    public LastOwnerException(String courses) {
        super("You are the only owner of " + courses
                + ". Hand each one to somebody else before deleting your account.");
    }
}
