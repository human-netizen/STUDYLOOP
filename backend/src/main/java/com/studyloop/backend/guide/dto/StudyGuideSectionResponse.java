package com.studyloop.backend.guide.dto;

import com.studyloop.backend.chat.dto.Citation;

import java.util.List;

// One section of a guide, as the page renders it.
//
// **`covered` is the field the whole phase turns on.** False means the course materials do not
// support this section: `body`, `diagram` and `citations` are all empty, and the client says so in
// the section's own place on the page rather than dropping it. A student who reads "your materials
// don't cover step 3" has learned something the guide could not otherwise tell them, and it is the
// output an ungrounded generator is structurally incapable of producing.
public record StudyGuideSectionResponse(
        int position,
        String heading,
        boolean covered,
        // Markdown, with [n] markers matching `citations` below. Null for a gap.
        String body,
        // Mermaid source, or null — which is the normal answer. See StudyGuidePlanner for what is
        // filtered out of it before it ever reaches a renderer.
        String diagram,
        // Numbered from one *within this section*: [2] here is this section's second source, not
        // the guide's. That is what per-section retrieval looks like from the outside.
        List<Citation> citations
) { }
