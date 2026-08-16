package com.studyloop.backend.analytics;

import com.studyloop.backend.config.AnalyticsProperties;
import com.studyloop.backend.document.VectorSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

// Records what students asked, so Phase 9.1 has something to aggregate. Called by ChatService on
// every turn, grounded or refused.
//
// Two methods rather than one with a boolean, because the difference between them is a rule and
// not a flag: a refused question gets no document attribution. Retrieval *does* hand back weak
// chunks when it refuses — that is exactly what the confidence gate looked at and rejected — and
// writing those into the heatmap would credit a lecture for a question it demonstrably could not
// answer. The lecture nobody's questions reach is the finding; burying it under near-misses would
// destroy the only signal the heatmap has.
//
// Unlike the semantic cache and the summary generator, nothing here is wrapped in a catch. Those
// two call a third party and must survive it being down; this is one INSERT into a local table
// with no outage mode. The only realistic failure is a schema that does not match the code, and a
// swallowed exception would turn that into an instructor page that is silently empty forever.
@Service
@RequiredArgsConstructor
public class QuestionLogService {

    private final AnalyticsProperties properties;
    private final QuestionEventRepository repository;

    // The question was answered from the materials. documentIds are the distinct documents behind
    // the chunks that grounded it — the per-lecture heat.
    public void recordGrounded(UUID courseId, UUID askedBy, String question, float[] questionVector,
                               Double topSimilarity, Collection<UUID> documentIds) {
        UUID eventId = insert(courseId, askedBy, question, questionVector, true, topSimilarity);
        if (eventId == null) {
            return;
        }
        // Deduped and order-preserving: six chunks routinely come from two or three documents,
        // and the composite primary key would reject the repeats anyway.
        Set<UUID> distinct = new LinkedHashSet<>(documentIds);
        distinct.remove(null);
        repository.insertEventDocuments(eventId, distinct);
    }

    // The confidence gate refused. topSimilarity is kept even so: "closest match was 0.24" and
    // "nothing in the course came close" are different problems, and only one of them is fixed by
    // uploading another document.
    //
    // Returns the row's id — null when logging is off — because a refusal is the one event a
    // student can act on: chat hands it back so "ask the class" can attach a forum thread to this
    // exact question rather than to a copy of its text (Phase 9.2).
    public UUID recordRefused(UUID courseId, UUID askedBy, String question, float[] questionVector,
                              Double topSimilarity) {
        return insert(courseId, askedBy, question, questionVector, false, topSimilarity);
    }

    // Whether a question id belongs to this course. The forum calls it before linking a thread to
    // a refusal, so a client cannot attach one course's thread to another course's question.
    public boolean isCourseQuestion(UUID courseId, UUID questionEventId) {
        return questionEventId != null && repository.existsInCourse(courseId, questionEventId);
    }

    // Returns null when logging is switched off, so callers skip the follow-up write too.
    private UUID insert(UUID courseId, UUID askedBy, String question, float[] questionVector,
                        boolean grounded, Double topSimilarity) {
        if (!properties.enabled()) {
            return null;
        }
        UUID eventId = UUID.randomUUID();
        // A null vector is stored as such: the question still counts toward the totals and the
        // ungrounded list, it just cannot be clustered with anything.
        String literal = questionVector == null ? null : VectorSupport.toLiteral(questionVector);
        repository.insertEvent(eventId, courseId, askedBy, question, literal, grounded, topSimilarity);
        return eventId;
    }
}
