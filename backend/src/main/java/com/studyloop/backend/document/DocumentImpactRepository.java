package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// What deleting one document would destroy, counted before it is destroyed (Phase 27.3).
//
// **This exists because "degrading is acceptable, silently degrading is the defect" applies to
// destructive actions too.** A delete button that says "are you sure?" is asking a question the
// reader has no way to answer: the consequences are spread over five tables they have never seen.
// A delete button that says "412 chunks · 7 flashcards lose their source · 23 questions lose their
// lecture attribution · 1 video loses 4 scene citations" is asking a question they can.
//
// Native SQL, and one round trip rather than five. Each count is a scalar subquery over a table
// this package does not own an entity for — flashcards, question_event_documents, forum_answers
// and video_scene_citations all belong to other packages, and reaching for their repositories
// would put a delete-time reporting concern into four of them. The subqueries index-hit on the
// foreign key each one filters by.
@Repository
@RequiredArgsConstructor
public class DocumentImpactRepository {

    private final JdbcTemplate jdbc;

    public DocumentImpact impactOf(UUID documentId) {
        return jdbc.queryForObject("""
                select
                    (select count(*) from document_chunks
                      where document_id = ?)                                       as chunks,
                    (select count(*) from flashcards
                      where source_document_id = ?)                                as flashcards,
                    (select count(*) from question_event_documents
                      where document_id = ?)                                       as questions,
                    (select count(*) from forum_answers
                      where source_document_id = ?)                                as forum_answers,
                    (select count(*) from video_scene_citations vsc
                       join document_chunks c on c.id = vsc.chunk_id
                      where c.document_id = ?)                                     as scene_citations,
                    (select count(distinct s.job_id) from video_scene_citations vsc
                       join video_scenes s on s.id = vsc.scene_id
                       join document_chunks c on c.id = vsc.chunk_id
                      where c.document_id = ?)                                     as videos
                """,
                (rs, row) -> new DocumentImpact(
                        rs.getInt("chunks"),
                        rs.getInt("flashcards"),
                        rs.getInt("questions"),
                        rs.getInt("forum_answers"),
                        rs.getInt("scene_citations"),
                        rs.getInt("videos")),
                documentId, documentId, documentId, documentId, documentId, documentId);
    }

    // The sixth reference has no count here and no foreign key anywhere: chat_cache_entries
    // .citations is jsonb written verbatim, so nothing in the database notices that a document it
    // names has gone. It is handled by dropping the course's cache outright
    // (SemanticCacheService.invalidate, which is what ingestion already calls), because the
    // alternative is scanning every cached answer's JSON for an id on a path that runs once per
    // delete. Counting it would also be a lie on screen — the entries are not lost, they are
    // recomputed on the next question.
    public record DocumentImpact(int chunks, int flashcards, int questions, int forumAnswers,
                                 int sceneCitations, int videos) {

        // "412 chunks · 7 flashcards lose their source · 1 video loses 4 scene citations", with the
        // zero rows left out. Built on the server rather than in the client, so the sentence and
        // the counts it is made of cannot disagree.
        public String describe() {
            StringBuilder text = new StringBuilder();
            append(text, chunks + " indexed passage" + plural(chunks));
            if (flashcards > 0) {
                append(text, flashcards + " flashcard" + plural(flashcards) + " lose their source");
            }
            if (questions > 0) {
                append(text, questions + " question" + plural(questions)
                        + " lose their lecture attribution");
            }
            if (forumAnswers > 0) {
                append(text, forumAnswers + " forum answer" + plural(forumAnswers)
                        + " lose their provenance");
            }
            if (videos > 0) {
                append(text, videos + " video" + plural(videos) + " lose " + sceneCitations
                        + " scene citation" + plural(sceneCitations));
            }
            return text.toString();
        }

        private static void append(StringBuilder text, String part) {
            if (!text.isEmpty()) {
                text.append(" · ");
            }
            text.append(part);
        }

        private static String plural(int count) {
            return count == 1 ? "" : "s";
        }
    }
}
