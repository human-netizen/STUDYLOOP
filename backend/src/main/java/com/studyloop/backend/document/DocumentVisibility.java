package com.studyloop.backend.document;

// Who a document may be used to answer (Phase 16.3).
//
// Every document before this phase was course material: uploaded by a manager, or written by the
// system from an answer a manager accepted. Both are curated, and "the course's corpus" and "the
// documents in this course" were the same set. A photographed note is neither — it is one member's
// notebook, uploaded by that member, and it can be wrong in ways a lecture handout is not.
//
// **Two failures, and they need different answers.** A private note that leaks into everyone's
// answers publishes a student's work to their class without asking. A private note that can never
// leave stays a personal transcription tool, which is the thing 16.3 exists to be more than. So
// the default is OWNER and the promotion is manager-gated — the same rule 9.2 applies to accepting
// a forum answer, and for the same reason: promoting writes member-authored text into the corpus
// that future answers may be grounded on and cite by name.
public enum DocumentVisibility {

    // Retrievable only for the member who uploaded it. Their own chat, quizzes and flashcards see
    // it; nobody else's do.
    OWNER,

    // Part of the course's corpus, retrievable for every member. Where all uploaded material and
    // every forum-derived document sits, and where a promoted note lands.
    COURSE
}
