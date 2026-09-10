package com.studyloop.backend.guide;

// No chat provider is configured, so nothing can be written. A 503 at the door rather than a
// FAILED row a minute later saying the same thing — the client already knows from the library's
// `available` flag, and this is what answers anyone who posts anyway.
public class StudyGuideUnavailableException extends RuntimeException {

    public StudyGuideUnavailableException() {
        super("Study guide generation is not available: no AI provider is configured.");
    }
}
