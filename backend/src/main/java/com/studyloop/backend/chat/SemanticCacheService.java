package com.studyloop.backend.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.SemanticCacheRepository.CacheRow;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.config.SemanticCacheProperties;
import com.studyloop.backend.document.EmbeddingClient;
import com.studyloop.backend.document.VectorSupport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The semantic answer cache (Phase 8.3). An exact-string cache would almost never fire — nobody
// types the same question twice, character for character — so the key is the question's
// *embedding*, and "have we answered this before" becomes a nearest-neighbour search. "What is
// optimal substructure?" and "explain optimal substructure" land in the same place.
//
// Two things keep that from serving wrong answers. The threshold is high enough (0.97) that a
// hit means paraphrase rather than same-topic, and entries are scoped per course, because the
// same question against different materials is a different answer.
//
// Nothing here is allowed to break a chat turn. Every path swallows its own failures: a cache
// that is down should cost money, not answers.
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    // Boot 4.1's modular web starter publishes no ObjectMapper bean (see BUGS.md), so — as
    // elsewhere in the project — we keep our own. Unknown fields are ignored so an entry written
    // by an older Citation shape still deserializes instead of poisoning the lookup.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final SemanticCacheProperties properties;
    private final SemanticCacheRepository repository;
    private final EmbeddingClient embeddingClient;

    // Embeds the question and looks for a near-identical one already answered in this course.
    //
    // The probe hands the vector back even on a miss, and that is the point: the caller passes it
    // straight to retrieval, so a miss costs one embedding call rather than two. Adding a cache
    // that made every uncached question more expensive would be a strange way to save money.
    public CacheProbe probe(UUID courseId, String question) {
        if (!properties.enabled() || !embeddingClient.isConfigured()) {
            return CacheProbe.unavailable();
        }
        try {
            float[] vector = embeddingClient.embedQuery(question);
            Optional<CacheRow> nearest = repository.findNearest(
                    courseId, VectorSupport.toLiteral(vector), Instant.now().minus(properties.ttl()));

            if (nearest.isEmpty() || nearest.get().similarity() < properties.minSimilarity()) {
                return CacheProbe.miss(vector);
            }

            CacheRow row = nearest.get();
            repository.markHit(row.id());
            log.debug("Semantic cache hit in course {} at similarity {}", courseId, row.similarity());
            return CacheProbe.hit(vector,
                    new CachedAnswer(row.answer(), readCitations(row.citationsJson()), row.similarity()));
        } catch (RuntimeException e) {
            // Including the embedding call: if the provider is unreachable, retrieval will hit
            // the same wall and report it properly. The cache's job is to stay out of the way.
            log.warn("Semantic cache probe failed for course {}: {}", courseId, e.getMessage());
            return CacheProbe.unavailable();
        }
    }

    // Remembers a freshly generated answer, reusing the vector the probe already computed so
    // writing to the cache is a plain INSERT with no provider call of its own.
    public void store(UUID courseId, String question, float[] questionVector, String answer,
                      List<Citation> citations) {
        if (!properties.enabled() || questionVector == null || answer == null || answer.isBlank()) {
            return;
        }
        try {
            repository.insert(courseId, question, VectorSupport.toLiteral(questionVector), answer,
                    objectMapper.writeValueAsString(citations));
        } catch (Exception e) {
            log.warn("Could not cache answer for course {}: {}", courseId, e.getMessage());
        }
    }

    // Drops a course's cached answers because its materials changed. Called after a document
    // finishes ingesting: the corpus that produced those answers no longer exists, and a question
    // that was refused yesterday may be answerable now.
    public void invalidate(UUID courseId) {
        try {
            int removed = repository.deleteByCourse(courseId);
            if (removed > 0) {
                log.info("Invalidated {} cached answers for course {}", removed, courseId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not invalidate the cache for course {}: {}", courseId, e.getMessage());
        }
    }

    // Cumulative counts for the cost dashboard. Owned here rather than queried from the usage
    // package so this table has exactly one reader.
    public CacheStats stats() {
        return new CacheStats(repository.countEntries(), repository.totalHits());
    }

    private List<Citation> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Citation>>() { });
        } catch (Exception e) {
            // Losing the source list is survivable; losing the answer is not.
            log.warn("Cached citations were unreadable: {}", e.getMessage());
            return List.of();
        }
    }

    // The result of a probe. `questionVector` is non-null whenever the cache actually ran, hit or
    // miss, and null when it was disabled, unconfigured or broken — in which case the caller
    // embeds the question itself, exactly as before this phase existed.
    public record CacheProbe(float[] questionVector, CachedAnswer hit) {

        static CacheProbe unavailable() {
            return new CacheProbe(null, null);
        }

        static CacheProbe miss(float[] questionVector) {
            return new CacheProbe(questionVector, null);
        }

        static CacheProbe hit(float[] questionVector, CachedAnswer hit) {
            return new CacheProbe(questionVector, hit);
        }

        public boolean isHit() {
            return hit != null;
        }
    }

    public record CachedAnswer(String answer, List<Citation> citations, double similarity) { }

    public record CacheStats(long entries, long hits) { }
}
