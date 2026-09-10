package com.studyloop.backend.guide.dto;

import com.studyloop.backend.document.Language;
import com.studyloop.backend.guide.StudyGuideStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A guide, as the page polls it and then renders it.
//
// The two counters are here rather than derived on the client because they say two different
// things at two different times. While it runs, `sectionsWritten` of `sectionsPlanned` is the
// progress line — real progress, out of a number the outline call actually decided, not a
// percentage invented to fill a bar. Once it is READY the same pair is the coverage report: six
// planned and four written means two of the sections this topic needs are not in the course.
public record StudyGuideResponse(
        UUID id,
        UUID courseId,
        String topic,
        StudyGuideStatus status,
        Language language,
        int sectionsPlanned,
        int sectionsWritten,
        // One outline call plus one per covered section — the number 22.4 is a claim about, read
        // back from the row rather than asserted.
        int modelCalls,
        // Why it stopped. Null for REFUSED, which is not a failure and needs no error text: the
        // status is the whole message, and the client offers Phase 20.2's next steps instead.
        String error,
        Instant createdAt,
        Instant completedAt,
        // Empty until the first section lands, then grows as they are written — so the page shows
        // the guide being written rather than a spinner followed by everything at once.
        List<StudyGuideSectionResponse> sections
) { }
