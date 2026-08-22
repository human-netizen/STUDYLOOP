package com.studyloop.backend.config;

import com.studyloop.backend.usage.QuotaGuard;
import com.studyloop.backend.usage.QuotaInterceptor;
import com.studyloop.backend.usage.QuotaInterceptor.Kind;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

// Where the quota guards are attached to actual URLs (Phase 10).
//
// The list is explicit rather than "everything under /api/v1", and that is the important part:
// every path below is one that reaches a paid provider, and every path not below is free. Listing
// them here keeps that judgement in one readable place instead of scattering an annotation across
// nine controllers, where a new endpoint that quietly calls the model would be ungated and
// nothing would say so.
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final QuotaGuard quotaGuard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Model calls. Each of these ends in a completion: an answer, a quiz, a set of cards, a
        // summary, a graded short answer, or the embedding of an accepted forum answer.
        registry.addInterceptor(new QuotaInterceptor(quotaGuard, Kind.AI, Set.of("POST")))
                .addPathPatterns(
                        "/api/v1/courses/*/chat",
                        "/api/v1/courses/*/chat/stream",
                        "/api/v1/courses/*/quizzes",
                        "/api/v1/courses/*/quizzes/*/attempts",
                        "/api/v1/courses/*/flashcards/generate",
                        "/api/v1/courses/*/documents/*/summary",
                        "/api/v1/courses/*/forum/threads/*/answers/*/accept");

        // Embedding calls. A search is one embedding of the query — far cheaper than a chat turn,
        // but not free, and it is the endpoint easiest to call in a loop.
        registry.addInterceptor(new QuotaInterceptor(quotaGuard, Kind.AI, Set.of("GET")))
                .addPathPatterns(
                        "/api/v1/courses/*/search",
                        "/api/v1/courses/*/retrieve");

        // Its own allowance: one upload is an embedding call per batch of chunks plus a summary,
        // so a handful of PDFs costs more than a day of asking questions.
        //
        // Notes are on the same allowance, and they belong there more than uploads do. A .pptx is
        // read locally and only its chunks are embedded; a photograph is a vision call before any
        // of that even starts, and unlike an upload every member of the course may make one —
        // Phase 10's ingest surface was sized for the handful of people who could reach it.
        registry.addInterceptor(new QuotaInterceptor(quotaGuard, Kind.UPLOAD, Set.of("POST")))
                .addPathPatterns(
                        "/api/v1/courses/*/documents",
                        "/api/v1/courses/*/notes");
    }
}
