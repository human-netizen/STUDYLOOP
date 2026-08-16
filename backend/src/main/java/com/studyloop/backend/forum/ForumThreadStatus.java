package com.studyloop.backend.forum;

// Where a thread stands. ANSWERED means a manager accepted one of its answers — which is also the
// moment that answer was written back into the course's corpus, so the two are never out of step.
public enum ForumThreadStatus {
    OPEN,
    ANSWERED
}
