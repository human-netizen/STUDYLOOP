package com.studyloop.backend.forum;

import java.util.UUID;

public class ForumThreadNotFoundException extends RuntimeException {

    public ForumThreadNotFoundException(UUID threadId) {
        super("Discussion " + threadId + " was not found.");
    }
}
