package com.studyloop.backend.guide;

import java.util.UUID;

// No such guide, or not this member's guide. One exception for both, because the alternative
// leaks the existence of other people's guides to anyone willing to guess a UUID.
public class StudyGuideNotFoundException extends RuntimeException {

    public StudyGuideNotFoundException(UUID guideId) {
        super("Study guide " + guideId + " was not found.");
    }
}
