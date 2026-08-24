package com.studyloop.backend.video;

import java.util.UUID;

// No such job, or not this member's job. One exception for both, because the alternative leaks
// the existence of other people's renders to anyone willing to guess a UUID.
public class VideoJobNotFoundException extends RuntimeException {

    public VideoJobNotFoundException(UUID jobId) {
        super("Video job " + jobId + " was not found.");
    }
}
