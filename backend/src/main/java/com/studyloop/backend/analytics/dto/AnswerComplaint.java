package com.studyloop.backend.analytics.dto;

import com.studyloop.backend.chat.dto.Citation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// One reported answer on the instructor's page (Phase 28.3).
//
// **It sits beside the ungrounded list because they are different signals that look alike.** A
// refusal is the corpus saying "I do not cover this"; a thumbs-down on a grounded answer is a
// reader saying "you do cover this and you got it wrong". The page showed only the first until this
// phase, which made every generation failure invisible: the answer was confident, cited, and
// nobody's dashboard ever heard about it.
//
// The asker is absent for the same reason `UngroundedQuestion` leaves them out: what the class
// cannot get right is analytics, who got it wrong is a different product.
public record AnswerComplaint(
        UUID questionEventId,
        String question,
        // Free text, and the field worth reading: what the right answer would have been, or which
        // lecture it should have found. Null when the reader clicked and said nothing.
        String reason,
        Instant reportedAt,
        // The passages the answer was shown with, exactly as they were on screen. This is what
        // separates "retrieval brought the wrong pages" from "retrieval was right and the writing
        // was wrong", which are the two failures that need opposite fixes.
        List<Citation> citations
) { }
