package com.studyloop.backend.guide;

// Where a study guide is in its life (Phase 22).
//
// The same shape as VideoJobStatus, which is itself the same shape as DocumentStatus: a linear
// walk with terminal states at the end, so the frontend polls it with the idiom it already has.
// A third polling protocol would be a third thing to get subtly wrong.
//
// REFUSED is here for the reason it is there: it is not a failure. It means the confidence gate
// looked at what the corpus holds on this topic and said no, before the outline call was made, at
// the cost of one embedding. FAILED means we tried and something broke. Collapsing the two would
// tell a student their guide crashed when in fact their course has no material on what they asked
// for — which is the one piece of information the answer actually contains.
public enum StudyGuideStatus {

    // Accepted, waiting for a slot on the executor.
    QUEUED,
    // Retrieving on the topic, gating, and asking the model for an outline. One model call.
    PLANNING,
    // Walking the outline: retrieve, gate, and write — or list a gap. One model call per covered
    // section and none at all per gap, which is why a guide over thin material is *cheaper* than
    // one over thick material rather than more expensive.
    WRITING,

    READY,
    FAILED,
    // The corpus cannot support the topic at all. Terminal, cheap, and the same decision chat
    // would have made about the same question.
    REFUSED;

    public boolean isTerminal() {
        return this == READY || this == FAILED || this == REFUSED;
    }

    // Whether a guide in this state was left mid-flight by a process that is no longer running.
    // Read only by the startup sweep — see StudyGuideReconciler for why the alternative is a
    // guide that says "writing section 3" forever.
    public boolean isUnfinished() {
        return !isTerminal();
    }
}
