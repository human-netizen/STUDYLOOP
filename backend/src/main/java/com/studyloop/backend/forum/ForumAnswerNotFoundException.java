package com.studyloop.backend.forum;

import java.util.UUID;

public class ForumAnswerNotFoundException extends RuntimeException {

    public ForumAnswerNotFoundException(UUID answerId) {
        super("Reply " + answerId + " was not found in this discussion.");
    }
}
