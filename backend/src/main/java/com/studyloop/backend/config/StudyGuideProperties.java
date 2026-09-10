package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Phase 22 — what a generated study guide is allowed to cost.
//
// **No `enabled` flag, deliberately, and the contrast with VideoProperties is the reason.** Video
// is off by default because it needs a sidecar container that an installation may simply not have,
// and a button that fails is worse than a feature that is absent. A guide needs the chat client
// and the retrieval pipeline — both of which every other feature in this product already requires
// — so the honest gate is `chatClient.isConfigured()`, which is the same gate summaries and
// quizzes are behind. A flag here would be a second switch that can disagree with the first.
//
// **No daily cap either, and that is also a contrast rather than an omission.** Phase 21 added one
// because a video is several orders of magnitude more expensive in *wall clock* than a chat turn,
// so the request-counting quota could not see the difference. A guide is six model calls — six
// chat answers — and the Phase 10.2 rolling token budget already prices exactly that. Adding a
// second limiter would be a second policy to keep in step with the first for no measurement that
// says it is needed.
@ConfigurationProperties(prefix = "studyloop.guide")
public record StudyGuideProperties(

        // The upper bound on one guide, and therefore on its cost: sections + 1 is the model-call
        // count in the worst case. Six is where a guide is still read rather than scrolled past,
        // and it is the same reasoning as the six scenes of a video.
        int maxSections,

        // Passages per section. The same k as a chat turn, for CorpusAnswerService's reason: a
        // different number here would mean a guide's sections were written from a different amount
        // of context than the chat answer to the same question, which is a difference nobody can
        // see and everybody has to explain.
        int retrievalK,

        // Whether the section prompt asks for a Mermaid diagram where the material has a structure
        // worth drawing (22.2). On by default; off costs nothing but the diagrams, because it is
        // one clause of one prompt and never a call of its own.
        boolean diagrams
) {

    public StudyGuideProperties {
        maxSections = maxSections > 0 ? maxSections : 6;
        retrievalK = retrievalK > 0 ? retrievalK : 6;
    }
}
