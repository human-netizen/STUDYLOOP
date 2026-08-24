package com.studyloop.backend.video;

// The per-member daily allowance is spent. 429, and the message says the number rather than
// "try again later", because a limit a user cannot see is indistinguishable from a bug.
public class VideoDailyCapExceededException extends RuntimeException {

    public VideoDailyCapExceededException(int cap) {
        super("You have already requested " + cap + " videos today. The daily limit is " + cap + ".");
    }
}
