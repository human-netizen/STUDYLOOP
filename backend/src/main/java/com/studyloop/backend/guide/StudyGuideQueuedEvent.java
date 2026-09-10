package com.studyloop.backend.guide;

import java.util.UUID;

// Published inside the request's transaction, delivered after it commits. Carries the requester
// because the guide is generated *as* them — retrieval must see exactly what they can see, their
// own private notes included — and because the tokens are theirs to be billed for.
public record StudyGuideQueuedEvent(UUID guideId, UUID requestedBy) { }
