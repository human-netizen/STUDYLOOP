package com.studyloop.backend.chat.dto;

import java.util.UUID;

// An answer that did not come from the course's materials (Phase 20.2).
//
// A separate response type from ChatResponse, with no `citations` field at all rather than an
// empty one. An empty list is something a client can forget to check; a type with no citations in
// it cannot be rendered as a grounded answer by accident, which is the only way this feature stays
// honest once someone else is writing the frontend.
public record GeneralAnswerResponse(
        UUID conversationId,
        String answer
) { }
