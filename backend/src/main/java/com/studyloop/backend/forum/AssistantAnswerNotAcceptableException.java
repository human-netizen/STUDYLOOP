package com.studyloop.backend.forum;

import java.util.UUID;

// Thrown when someone tries to accept the assistant's own reply into the course's corpus.
// See ForumAnswerAuthor for why that must not be possible, and ForumService.accept for why it is
// a 409 rather than a 403.
public class AssistantAnswerNotAcceptableException extends RuntimeException {

    public AssistantAnswerNotAcceptableException(UUID answerId) {
        super("That answer was written by the assistant, so it can't be added to the course "
              + "materials. Accept a reply written by a person instead. (" + answerId + ")");
    }
}
