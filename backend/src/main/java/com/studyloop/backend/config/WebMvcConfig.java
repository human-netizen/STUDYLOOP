package com.studyloop.backend.config;

import com.studyloop.backend.usage.QuotaGuard;
import com.studyloop.backend.usage.QuotaInterceptor;
import com.studyloop.backend.usage.QuotaInterceptor.Kind;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Set;

// Where the quota guards are attached to actual URLs (Phase 10).
//
// The list is explicit rather than "everything under /api/v1", and that is the important part:
// every path below is one that reaches a paid provider, and every path not below is free. Listing
// them here keeps that judgement in one readable place instead of scattering an annotation across
// nine controllers, where a new endpoint that quietly calls the model would be ungated and
// nothing would say so.
//
// **That is exactly what happened, and it is why the three lists are now constants** (2026-09-11).
// Phase 27.2 added `POST /courses/{id}/documents/{docId}/reingest`, which re-extracts and
// re-embeds a whole document, and no pattern here matched it for two phases — `*` matches one path
// segment, so `/courses/*/documents` covers the collection and nothing beneath it. Naming the lists
// lets `WebMvcConfigQuotaCoverageTest` state the policy independently and fail when an endpoint
// reaching a provider is not on it, which is a guard rather than a habit of remembering.
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    // Model calls. Each of these ends in a completion: an answer, a quiz, a set of cards, a
    // summary, a graded short answer, or the embedding of an accepted forum answer.
    public static final List<String> AI_POST_PATHS = List.of(
            "/api/v1/courses/*/chat",
            "/api/v1/courses/*/chat/stream",
            "/api/v1/courses/*/chat/general",
            "/api/v1/courses/*/quizzes",
            "/api/v1/courses/*/quizzes/*/attempts",
            "/api/v1/courses/*/flashcards/generate",
            "/api/v1/courses/*/documents/*/summary",
            "/api/v1/courses/*/forum/threads/*/answers/*/accept",
            // Phase 21. By far the most expensive entry on this list — up to fourteen model calls
            // behind one POST — and the only one whose spending happens after the response has been
            // sent. The interceptor still belongs here: it is the door, and a door that only guards
            // cheap requests is guarding the wrong thing. The feature has a second, tighter limit of
            // its own (a per-member daily cap) because a quota sized for chat turns would let one
            // person spend an afternoon of the machine before this one noticed.
            "/api/v1/courses/*/videos",
            // Phase 22. Six completions behind one POST, and — unlike the video — no second limiter
            // behind it, because six completions is exactly what the rolling token budget already
            // prices. A guide is expensive in the currency this quota counts, which is the case the
            // quota was built for.
            "/api/v1/courses/*/guides");

    // Embedding calls. A search is one embedding of the query — far cheaper than a chat turn, but
    // not free, and it is the endpoint easiest to call in a loop.
    public static final List<String> AI_GET_PATHS = List.of(
            "/api/v1/courses/*/search",
            "/api/v1/courses/*/retrieve");

    // Its own allowance: one upload is an embedding call per batch of chunks plus a summary, so a
    // handful of PDFs costs more than a day of asking questions.
    //
    // Notes are on the same allowance, and they belong there more than uploads do. A .pptx is read
    // locally and only its chunks are embedded; a photograph is a vision call before any of that
    // even starts, and unlike an upload every member of the course may make one — Phase 10's
    // ingest surface was sized for the handful of people who could reach it.
    public static final List<String> UPLOAD_POST_PATHS = List.of(
            "/api/v1/courses/*/documents",
            "/api/v1/courses/*/notes",
            // Phase 27.2's re-ingest, added 2026-09-11 because it never was.
            //
            // **A re-ingest costs what the upload cost**: the stored bytes are read again, a scanned
            // page goes to the vision model again, and every chunk is embedded again. It belongs on
            // the UPLOAD allowance rather than AI for that reason — it is the same work, not a
            // cheaper relative of it — and unlike an upload it is idempotent by replace, so nothing
            // stops it being called in a loop over one document.
            "/api/v1/courses/*/documents/*/reingest");

    private final QuotaGuard quotaGuard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new QuotaInterceptor(quotaGuard, Kind.AI, Set.of("POST")))
                .addPathPatterns(AI_POST_PATHS);

        registry.addInterceptor(new QuotaInterceptor(quotaGuard, Kind.AI, Set.of("GET")))
                .addPathPatterns(AI_GET_PATHS);

        registry.addInterceptor(new QuotaInterceptor(quotaGuard, Kind.UPLOAD, Set.of("POST")))
                .addPathPatterns(UPLOAD_POST_PATHS);
    }
}
