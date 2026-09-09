package com.studyloop.backend.retrieval;

import java.util.List;
import java.util.UUID;

// Phase 28.2 — which documents a search is allowed to look at.
//
// Retrieval has been course-wide since Phase 5, which is right for "what does this course say about
// X" and wrong for the night before an exam: a student revising one lecture is searching thirteen
// others for no reason, and the fused top-6 can be filled by a chapter they are not reading.
//
// **Empty means the whole course, and that is what makes this safe to add.** Every caller that
// existed before this phase passes nothing and gets exactly the search it got before — the
// predicate is not in the SQL at all, so there is no plan change, no new index requirement, and no
// eval number that moves. The eval harness runs unscoped for that reason.
//
// **This is not 23.2's taxonomy and does not replace it.** That filters on metadata which does not
// exist yet — tags, week number, theory-versus-lab — and needs a migration and an extraction step
// to infer it. This filters on document ids, which exist, and is chosen by the reader rather than
// inferred from the question.
//
// A record rather than a bare `List<UUID>` so the empty case has a name: `isWholeCourse()` reads as
// the decision it is, where `ids.isEmpty()` at four call sites reads as a null check somebody may
// helpfully "fix".
public record DocumentScope(List<UUID> documentIds) {

    // The scope every caller before Phase 28.2 had, and the one every caller that does not care
    // still has.
    public static final DocumentScope WHOLE_COURSE = new DocumentScope(List.of());

    // Null, empty and "all of them" are the same request and collapse to the same object. Nulls
    // *within* the list are dropped rather than rejected: a client that sends one is asking for
    // the documents it named, and a null id is not one of them.
    public static DocumentScope of(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return WHOLE_COURSE;
        }
        List<UUID> ids = documentIds.stream().filter(id -> id != null).distinct().toList();
        return ids.isEmpty() ? WHOLE_COURSE : new DocumentScope(ids);
    }

    public boolean isWholeCourse() {
        return documentIds.isEmpty();
    }

    public int size() {
        return documentIds.size();
    }
}
