package com.studyloop.backend.video;

// Asked to remove a job that is still being made. 409: the request is well formed and the state is
// wrong, and the state will change on its own within minutes.
//
// It exists rather than letting the delete through because the runner is at that moment writing
// statuses onto the row and files into the directory. Deleting either underneath it turns a
// finished render into a foreign-key violation in a background thread, which is a stack trace in
// the log and nothing at all on the screen.
public class VideoJobRunningException extends RuntimeException {

    public VideoJobRunningException() {
        super("This video is still being made. Wait for it to finish.");
    }
}
