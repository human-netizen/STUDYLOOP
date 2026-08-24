package com.studyloop.backend.video;

// The feature is switched off, or this installation has no renderer. 503 rather than 404, because
// the endpoint exists and the resource does not: a client that gets a 404 concludes it typed the
// URL wrong, and a client that gets a 503 with a reason can say so to the person.
//
// In practice nobody sees this. The flag also decides whether the UI draws the button, so reaching
// the endpoint with the feature off means a hand-written request or a stale tab — both of which
// deserve the honest answer rather than a stack trace.
public class VideoDisabledException extends RuntimeException {

    public VideoDisabledException(String message) {
        super(message);
    }
}
