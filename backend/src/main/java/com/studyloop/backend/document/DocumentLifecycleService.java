package com.studyloop.backend.document;

import com.studyloop.backend.chat.SemanticCacheService;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.InsufficientCourseRoleException;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.document.DocumentImpactRepository.DocumentImpact;
import com.studyloop.backend.document.dto.DocumentResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Taking a document back out — the half twenty-six phases never built (Phase 27.3).
//
// **Two verbs, because the two things people mean by "delete" have different prices.**
//
//   Retire   status = RETIRED and nothing else. The row stays, the bytes stay, the chunks and the
//            vectors stay. Out of every answer, still in the library, still openable behind a
//            citation somebody was handed last week. One field, reversible by one field.
//
//   Delete   the row, its chunks, its vectors and its bytes, with what that destroys counted on
//            screen before it runs. Not reversible, and the confirmation says so in numbers.
//
// Retire is the one that should be reached for, which is why it is offered first and why it is
// free: every candidate branch in ChunkSearchRepository already filters `d.status = 'READY'`, and
// quiz and flashcard generation gate on it too, so a status that is not READY is invisible to all
// of them with no query changed and no migration. Retiring deliberately does *not* delete the
// chunks — that was the first design and it is worse, because it breaks the video citations,
// makes un-retiring a full re-embed with a provider bill, and turns one update into a pipeline.
//
// **There is no `deleted_at` column, and the reason is a constraint rather than a preference.**
// `unique (course_space_id, sha256)` is a database guarantee. A soft-deleted row keeps holding
// that key, so re-uploading the file lands on the dedupe path in DocumentService.accept and hands
// the user back the document they thought they had deleted — no error, nothing to point at.
// Retire avoids that by not pretending: the row is visibly there and marked. Delete avoids it by
// actually removing the row, which makes re-uploading work again, and that is what a person
// expects after deleting something.
@Service
@RequiredArgsConstructor
public class DocumentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DocumentLifecycleService.class);

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentNoteBlockRepository noteBlockRepository;
    private final DocumentTermRepository termRepository;
    private final DocumentImpactRepository impactRepository;
    private final DocumentStorageService storageService;
    private final SemanticCacheService semanticCache;
    private final CourseAccess courseAccess;
    private final ApplicationEventPublisher eventPublisher;

    // What a delete would destroy, so the client can say it before asking. Read-only and cheap
    // enough to fetch when a confirmation opens rather than with the list.
    @Transactional(readOnly = true)
    public DocumentImpact impact(UUID actorId, UUID courseId, UUID documentId) {
        require(actorId, courseId, documentId);
        return impactRepository.impactOf(documentId);
    }

    // Out of the corpus, still in the library.
    //
    // Refuses a document that never finished ingesting: FAILED and UPLOADED are already invisible
    // to every reader, so retiring one would be a no-op the UI had offered as an action. Delete is
    // the verb for those.
    @Transactional
    public DocumentResponse retire(UUID actorId, UUID courseId, UUID documentId) {
        Document document = require(actorId, courseId, documentId);
        if (document.getStatus() != DocumentStatus.READY) {
            throw new DocumentNotRetirableException(document.getStatus());
        }
        document.setStatus(DocumentStatus.RETIRED);
        document.setStage(IngestionProgress.stageOf(DocumentStatus.RETIRED));
        documentRepository.saveAndFlush(document);
        // Nothing points at documents from chat_cache_entries, so no cascade and no foreign key
        // will do this. A cached answer written while the document was in the corpus would go on
        // citing it — and would go on being served — for as long as the entry lives.
        semanticCache.invalidate(courseId);
        log.info("Retired document {} in course {}", documentId, courseId);
        return DocumentResponse.from(document, courseId);
    }

    // Back into the corpus. One field, no provider call, no re-embed — which is the whole argument
    // for retiring rather than deleting the chunks.
    @Transactional
    public DocumentResponse unretire(UUID actorId, UUID courseId, UUID documentId) {
        Document document = require(actorId, courseId, documentId);
        if (document.getStatus() != DocumentStatus.RETIRED) {
            throw new DocumentNotRetirableException(document.getStatus());
        }
        document.setStatus(DocumentStatus.READY);
        document.setStage(IngestionProgress.stageOf(DocumentStatus.READY));
        documentRepository.saveAndFlush(document);
        // The other direction needs it just as much: every refusal cached while this document was
        // out of the corpus is a question it may now answer.
        semanticCache.invalidate(courseId);
        log.info("Un-retired document {} in course {}", documentId, courseId);
        return DocumentResponse.from(document, courseId);
    }

    // Gone: the chunks and their vectors, the glossary, the note blocks, the row, then the
    // stored bytes.
    //
    // **The children this package maps are deleted explicitly, even though the schema already
    // cascades them.** `on delete cascade` is a statement to Postgres and Hibernate cannot see
    // it: chunks loaded in this persistence context go on referencing a Document that has been
    // removed, and the flush fails with a TransientPropertyValueException naming a column
    // nobody touched. It shows up wherever a delete shares a transaction with the ingest that
    // wrote the rows, which is every test that seeds its own corpus — and it is worth fixing
    // here rather than in the test, because a delete that names what it destroys is also the
    // thing the impact counts promise. The database cascade stays as the backstop for the
    // tables this package does not own.
    //
    // **Row first, bytes second, and that ordering is deliberate.** These are two stores that
    // cannot share a transaction — Postgres and a bucket reached over HTTP — so one of the two
    // orphans is unavoidable when the second write fails. An orphaned object costs disk and
    // nothing else; an orphaned row is a 500 in the citation viewer for every reader who clicks
    // it. The bytes go after the commit for the same reason ingestion starts after one: a
    // rolled-back transaction must not have destroyed anything.
    @Transactional
    public DocumentImpact delete(UUID actorId, UUID courseId, UUID documentId) {
        Document document = require(actorId, courseId, documentId);
        DocumentImpact impact = impactRepository.impactOf(documentId);
        String storagePath = document.getStoragePath();

        chunkRepository.deleteByDocumentId(documentId);
        noteBlockRepository.deleteByDocumentId(documentId);
        termRepository.deleteByDocumentId(documentId);
        documentRepository.delete(document);
        documentRepository.flush();
        semanticCache.invalidate(courseId);

        if (storagePath != null) {
            eventPublisher.publishEvent(new DocumentBytesOrphanedEvent(storagePath));
        }
        log.info("Deleted document {} in course {} - {}", documentId, courseId, impact.describe());
        return impact;
    }

    // Removes the object once the delete has committed. A failure here is logged and swallowed:
    // the document is already gone from everybody's point of view, and re-throwing would turn a
    // successful delete into a 500 that invites a retry which can no longer find the row.
    void removeBytes(String storagePath) {
        try {
            storageService.delete(storagePath);
        } catch (RuntimeException e) {
            log.warn("Deleted the document row but could not remove its bytes at {}: {}",
                    storagePath, e.getMessage());
        }
    }

    // **Manager-only, with one exception, and the exception is read off the column rather than
    // guessed.** Uploading material is manager-only, so removing it is too. A handwritten note
    // whose visibility is still OWNER is a different thing: `visibility = OWNER` already says the
    // course cannot see it, so its owner deleting it is not touching the course's corpus at all.
    private Document require(UUID actorId, UUID courseId, UUID documentId) {
        Membership actor = courseAccess.requireMember(actorId, courseId);
        Document document = documentRepository.findVisibleById(documentId, courseId, actorId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        boolean mine = document.getUploadedBy().getId().equals(actorId);
        boolean privateNote = document.getVisibility() == DocumentVisibility.OWNER;
        if (privateNote && mine) {
            return document;
        }
        if (actor.getRole() == MembershipRole.MEMBER) {
            throw new InsufficientCourseRoleException(courseId);
        }
        return document;
    }

    // Carries the storage path past the commit so the bytes are removed only once the row really
    // is gone. A record rather than a bare String so the listener cannot be woken by anything else
    // that happens to publish one.
    public record DocumentBytesOrphanedEvent(String storagePath) { }
}
