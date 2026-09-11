package com.studyloop.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 10's quota is only worth what its URL list covers, and on 2026-09-11 the list was found to
// be missing an endpoint for two phases: Phase 27.2's re-ingest re-extracts and re-embeds an entire
// document — vision calls included — and no pattern in WebMvcConfig matched it, so nothing refused
// it however many times it was called.
//
// **The bug was not a forgotten line, it was a pattern that looks like it covers more than it does.**
// `*` matches exactly one path segment, so `/api/v1/courses/*/documents` matches the collection and
// nothing beneath it. Reading the list, `documents` appears to be handled.
//
// So this test states the policy a second time, independently, in the terms a reader cares about —
// concrete URLs that cost money, and concrete URLs that do not — and fails when the two statements
// disagree. A new endpoint that reaches a provider now has to be classified here before the suite
// goes green, which is the part a comment asking people to remember cannot do.
//
// It parses with PathPatternParser because that is what Spring MVC and MappedInterceptor use to
// match these patterns at runtime; testing against a different matcher would prove nothing about
// production.
class WebMvcConfigQuotaCoverageTest {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    private static final String COURSE = "11111111-1111-1111-1111-111111111111";
    private static final String DOC = "22222222-2222-2222-2222-222222222222";

    private static boolean matches(List<String> patterns, String url) {
        PathContainer path = PathContainer.parsePath(url);
        return patterns.stream().map(PARSER::parse).anyMatch(pattern -> pattern.matches(path));
    }

    private static String course(String suffix) {
        return "/api/v1/courses/" + COURSE + suffix;
    }

    // ----------------------------------------------------------------------------------------
    // The regression guard: the endpoint that was missed
    // ----------------------------------------------------------------------------------------

    @Test
    void reingestIsOnTheUploadAllowance() {
        // The whole reason this file exists. A re-ingest is the same work as the upload that
        // preceded it, so it belongs on the same allowance rather than on the cheaper AI one.
        assertThat(matches(WebMvcConfig.UPLOAD_POST_PATHS, course("/documents/" + DOC + "/reingest")))
                .as("POST .../documents/{id}/reingest re-extracts and re-embeds a whole document; "
                    + "it must be gated")
                .isTrue();
    }

    @Test
    void theCollectionPatternNeverCoveredAnythingBeneathIt() {
        // The lesson, pinned so it cannot be un-learned: this is *why* the endpoint above went
        // ungated for two phases, and it is the shape of any future instance of the same mistake.
        assertThat(matches(List.of("/api/v1/courses/*/documents"),
                course("/documents/" + DOC + "/reingest")))
                .as("a single * matches one path segment — a collection pattern does not cover "
                    + "its members' sub-resources")
                .isFalse();
    }

    // ----------------------------------------------------------------------------------------
    // Every endpoint that reaches a paid provider
    // ----------------------------------------------------------------------------------------

    @Test
    void everyCompletionEndpointIsGated() {
        List<String> endsInACompletion = List.of(
                course("/chat"),
                course("/chat/stream"),
                course("/chat/general"),
                course("/quizzes"),
                course("/quizzes/33333333-3333-3333-3333-333333333333/attempts"),
                // Phase 28.5 re-serves the questions you got wrong. It is a different URL from a
                // normal attempt and grading it still calls the model for short answers, so it has
                // to be gated too — it is covered because `wrong-answers` is a single segment and
                // therefore matches `/quizzes/*/attempts`. Asserted rather than assumed.
                course("/quizzes/wrong-answers/attempts"),
                course("/flashcards/generate"),
                course("/documents/" + DOC + "/summary"),
                course("/forum/threads/44444444-4444-4444-4444-444444444444"
                       + "/answers/55555555-5555-5555-5555-555555555555/accept"),
                course("/videos"),
                course("/guides"));

        assertThat(endsInACompletion)
                .allSatisfy(url -> assertThat(matches(WebMvcConfig.AI_POST_PATHS, url))
                        .as("%s reaches a completion and must be gated", url)
                        .isTrue());
    }

    @Test
    void everyEmbeddingReadIsGated() {
        assertThat(List.of(course("/search"), course("/retrieve")))
                .allSatisfy(url -> assertThat(matches(WebMvcConfig.AI_GET_PATHS, url))
                        .as("%s embeds the query and must be gated", url)
                        .isTrue());
    }

    @Test
    void everyIngestEndpointIsOnTheUploadAllowance() {
        assertThat(List.of(
                course("/documents"),
                course("/notes"),
                course("/documents/" + DOC + "/reingest")))
                .allSatisfy(url -> assertThat(matches(WebMvcConfig.UPLOAD_POST_PATHS, url))
                        .as("%s extracts and embeds, and must be on the upload allowance", url)
                        .isTrue());
    }

    // ----------------------------------------------------------------------------------------
    // And the other half: a quota that gates free endpoints is its own defect
    // ----------------------------------------------------------------------------------------

    @Test
    void endpointsThatReachNoProviderAreNotGated() {
        // Over-gating is not harmless: it spends a student's daily allowance on actions that cost
        // nothing, so the limit stops meaning what it says. Phase 28 is the reason this test is
        // worth writing — five read endpoints and not one of them makes a provider call.
        List<String> free = List.of(
                course("/chat/feedback"),            // 28.3 — reports a bad answer, calls nothing
                course("/documents/" + DOC + "/retire"),
                course("/documents/" + DOC + "/unretire"),
                course("/notes/66666666-6666-6666-6666-666666666666/promote"),
                course("/notes/66666666-6666-6666-6666-666666666666/demote"),
                course("/archive"),
                course("/unarchive"),
                course("/forum/threads"),            // posting a thread embeds nothing
                "/api/v1/courses",                   // creating a course
                "/api/v1/auth/login",
                "/api/v1/auth/register");

        assertThat(free).allSatisfy(url -> {
            assertThat(matches(WebMvcConfig.AI_POST_PATHS, url))
                    .as("%s reaches no provider and must not spend the AI allowance", url)
                    .isFalse();
            assertThat(matches(WebMvcConfig.UPLOAD_POST_PATHS, url))
                    .as("%s reaches no provider and must not spend the upload allowance", url)
                    .isFalse();
        });
    }
}
