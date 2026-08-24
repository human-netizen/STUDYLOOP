package com.studyloop.backend.video;

import java.util.UUID;

// Published when a job row is written, consumed after that transaction commits.
//
// The actor rides along rather than being read back off the job, for the reason the ingestion
// event carries the uploader: the executor thread has no request behind it, so the usage
// attribution set at the edge is not in force there, and every model call the render makes would
// otherwise be billed to nobody.
public record VideoJobQueuedEvent(UUID jobId, UUID requestedBy) { }
