package com.studyloop.backend.guide;

// The provider failed, or answered with something this side cannot read.
//
// Never surfaced as an HTTP status: by the time it is thrown the request has long since returned
// 202, so the runner catches it and writes it onto the guide as a FAILED with a reason. That is
// the difference between a job row and a plain @Async method — the failure has somewhere to go.
public class StudyGuideException extends RuntimeException {

    public StudyGuideException(String message) {
        super(message);
    }

    public StudyGuideException(String message, Throwable cause) {
        super(message, cause);
    }
}
