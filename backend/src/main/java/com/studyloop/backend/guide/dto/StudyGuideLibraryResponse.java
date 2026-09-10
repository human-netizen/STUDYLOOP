package com.studyloop.backend.guide.dto;

import java.util.List;

// The member's own guides for this course, plus whether this installation can make one at all.
//
// One call, so the page cannot draw a button from a flag it fetched at a different moment than the
// list — the same shape as VideoLibraryResponse and for the same reason. `available` is
// `chatClient.isConfigured()` and nothing else: there is no feature flag for guides, because the
// only thing they need beyond retrieval is the chat client that summaries and quizzes already
// require, and a second switch is a second thing that can disagree with the first.
public record StudyGuideLibraryResponse(
        boolean available,
        List<StudyGuideResponse> guides
) { }
