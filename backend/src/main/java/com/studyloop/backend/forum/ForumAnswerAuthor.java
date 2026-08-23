package com.studyloop.backend.forum;

// What wrote a reply. Two values, and the distinction is load-bearing rather than cosmetic:
// accepting a reply writes it into the course's corpus, and only MEMBER text may be accepted.
//
// The rule is the one thing Phase 20.1 must not get wrong. A corpus that can absorb the model's
// own output is a corpus that grows on its own output — the next answer is grounded on the last
// one, the citation says "the course materials", and nothing in the chain records that no human
// ever agreed with it. That is precisely the failure mode this project criticised in the design
// it borrowed the feature from, and the guard against it is here, at the type level, rather than
// in a comment on the accept endpoint.
public enum ForumAnswerAuthor {

    // A person in the course. Has a created_by, may be accepted into the corpus.
    MEMBER,

    // The assistant, posted by the corpus watch when a new document made the thread answerable.
    // No author, never acceptable, and the thread stays OPEN underneath it.
    ASSISTANT
}
