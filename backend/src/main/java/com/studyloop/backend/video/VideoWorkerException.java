package com.studyloop.backend.video;

// The renderer could not be reached, or answered with something this side does not understand.
//
// Never surfaced as an HTTP status: by the time it is thrown the request has long since returned
// 202, so it is caught by the runner and written onto the job as a FAILED with a reason. That is
// the whole difference between a job queue and an async method — the failure has somewhere to go.
public class VideoWorkerException extends RuntimeException {

    public VideoWorkerException(String message) {
        super(message);
    }

    public VideoWorkerException(String message, Throwable cause) {
        super(message, cause);
    }
}
