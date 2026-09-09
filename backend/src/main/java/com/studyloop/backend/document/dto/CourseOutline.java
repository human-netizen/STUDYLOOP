package com.studyloop.backend.document.dto;

import java.util.List;
import java.util.UUID;

// Phase 28.4 — what is actually in this course, for the person studying it.
//
// The instructor gets a confusion heatmap. A student got a list of filenames, which is the table of
// contents of a folder rather than of a subject. Everything here is read from columns that have
// been written on every ingest since Phase 13.4 and read by exactly one class: `section_path` fed
// small-to-big expansion and nothing else, and `document_terms` fed the glossary on one page.
//
// **"Never asked" is the useful half.** The instructor's page reports what confused people; this
// reports what nobody has touched, which a week before an exam is the more actionable of the two.
public record CourseOutline(
        List<DocumentOutline> documents,
        // How many of the documents above have never grounded a single question. Computed here
        // rather than counted client-side because it is the headline the page leads with.
        int neverAsked
) {

    // One document, its structure, and how much traffic it has seen.
    //
    // **Heat is document-level and this record says so rather than inventing a join.**
    // `question_event_documents` is `(question_event_id, document_id)`: the *chunks* a question
    // grounded on are not stored, so per-section attribution is not available without a schema
    // change. Document-level heat over section-level structure is the honest version, and it is
    // enough for the thing this page is for.
    public record DocumentOutline(
            UUID documentId,
            String filename,
            int pageCount,
            int chunkCount,
            // Distinct questions this document has ever grounded. All-time rather than windowed:
            // "nobody has ever asked about chapter 9" is the claim being made, and a 30-day window
            // would make every document look untouched in a quiet week.
            int questionCount,
            List<SectionOutline> sections
    ) {
    }

    // One section of one document, in document order.
    //
    // `terms` are the glossary entries 8.2 generated for this document that actually appear in this
    // section's text. The generator does not record which section a term came from — it reads the
    // document — so the attribution is made here by looking, which is why a term can appear under
    // two sections and why one can appear under none.
    public record SectionOutline(
            String sectionPath,
            Integer firstPage,
            Integer lastPage,
            int chunkCount,
            List<String> terms
    ) {
    }
}
