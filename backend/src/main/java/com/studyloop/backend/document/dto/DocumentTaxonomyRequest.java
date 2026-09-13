package com.studyloop.backend.document.dto;

import com.studyloop.backend.document.DocumentCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

// Phase 23.2 — a document's complete taxonomy, sent as a whole.
//
// **A PUT body, and the contrast with Phase 27.4's PATCH is the reason.** A course rename is a
// PATCH because the client holds one field and must not be made responsible for round-tripping a
// description it never read; `null` there means "leave it alone". Taxonomy is the opposite shape:
// the editor shows all three fields at once, so the client always holds the whole thing, and
// stating the whole target state removes the question a PATCH would have to answer — how to spell
// "clear the week" when `null` already means "don't touch it". Here `null` means *none*, because
// the body is a complete statement rather than a fragment.
//
// That also settles the tag set. A partial update of a set has two readings — add these, or these
// are now all of them — and no way to tell them apart on the wire. A PUT has only the second.
public record DocumentTaxonomyRequest(

        // Null when this material does not belong to a week. Bounded here as well as by the
        // column's check constraint, so a bad value is a 400 naming the field rather than a 500
        // carrying a constraint name.
        @Min(value = 1, message = "Week must be at least 1.")
        @Max(value = 52, message = "Week must be at most 52.")
        Integer week,

        // Null is accepted and read as UNCLASSIFIED, so clearing a category and never setting one
        // are the same request. An unknown string is a 400 from Jackson's enum binding, which is
        // the right answer: this is a closed vocabulary and a client sending a new value is
        // wrong rather than ahead.
        DocumentCategory category,

        // Normalised — lower-cased, single-spaced, de-duplicated — before anything is stored, so
        // "Dynamic Programming" and "dynamic  programming" are one tag and not three.
        @Size(max = 20, message = "A document can carry at most 20 tags.")
        List<@Size(max = 40, message = "A tag is at most 40 characters.") String> tags
) {
}
