package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

// Owns the persisted status transitions of the ingestion state machine. Each call is its
// own transaction, so a poller watching GET /documents/{id} sees the document advance
// through EXTRACTING → CHUNKING → EMBEDDING in real time. Kept a separate bean from the
// orchestrator so its @Transactional boundaries actually apply (self-invocation wouldn't).
//
// **Every write here is an explicit saveAndFlush rather than a dirty-checked field assignment**,
// which is not belt-and-braces. Most of what reads these rows back is native SQL — retrieval, the
// chunk queries, the analytics — and JdbcTemplate does not go through the EntityManager, so it
// never triggers Hibernate's automatic flush. Leaving the status in the persistence context means
// the row the pipeline's *own* next query sees still says EMBEDDING, and the failure is silent:
// every query filtering on `status = 'READY'` simply returns nothing, exactly as it would for a
// document that had no chunks. Phase 20.1's corpus watch is the first step to read a document it
// has just marked READY, and it found this.
@Service
@RequiredArgsConstructor
public class DocumentStatusService {

    private static final int MAX_ERROR_LENGTH = 500;

    private final DocumentRepository documentRepository;

    // Advances the document to a non-terminal/terminal status, clearing any prior error.
    //
    // Phase 25.2 — the same write also moves the progress bar to the floor of the band the new
    // status owns, and writes that status's default sentence. **One write rather than two**, so
    // the bar and the badge are rendered from a single row that was never momentarily
    // inconsistent: a client polling between two writes would otherwise catch EMBEDDING sitting at
    // extraction's percentage, which looks exactly like a stuck pipeline.
    @Transactional
    public void markStatus(UUID documentId, DocumentStatus status) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        document.setStatus(status);
        document.setErrorMessage(null);
        document.setProgress(IngestionProgress.floorOf(status));
        document.setStage(IngestionProgress.stageOf(status));
        documentRepository.saveAndFlush(document);
    }

    // Where the running step has got to, without moving the state machine (Phase 25.2). Called
    // once per routed page and once per embedding batch — the only two parts of an ingest long
    // enough that a student needs to be told it is still alive. VideoJobStatusService has the same
    // pair of methods for the same reason.
    //
    // **Never moves the bar backwards.** The steps report into bands, and a step that finishes
    // early would otherwise let the next status's floor be undone by a late report from the last
    // one — a bar that goes backwards reads as a restart, and nothing here ever restarts.
    @Transactional
    public void markProgress(UUID documentId, int percent, String stage) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        int next = Math.max(percent, document.getProgress());
        // Nothing to say: the percentage rounded to the same integer and the sentence is unchanged.
        // Fifteen routed pages inside a run that already writes hundreds of chunk rows is not an
        // expense worth guarding against — the guard is here so a forty-page cap cannot write forty
        // identical rows for a reader watching a bar that does not move.
        if (next == document.getProgress() && Objects.equals(stage, document.getStage())) {
            return;
        }
        document.setProgress(next);
        document.setStage(stage);
        documentRepository.saveAndFlush(document);
    }

    // Phase 19.1, and a separate write from markStatus rather than a parameter on it because the
    // two answer to different things: status is where the pipeline is, language is what it found.
    // It lands in its own transaction for the same reason every other write here does — a client
    // polling the document sees the language as soon as extraction has finished, not after the
    // embedding pass it has nothing to do with.
    @Transactional
    public void markLanguage(UUID documentId, Language language) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        document.setLanguage(language);
        documentRepository.saveAndFlush(document);
    }

    // Terminal failure: records the reason (truncated to fit the column) for the client.
    //
    // **Leaves `progress` where it stopped** rather than resetting it, which is Phase 25.2's one
    // deliberate asymmetry: how far a failed ingest got is the most useful thing its row can say,
    // and a document that failed at 92% is a different problem from one that failed at 6%.
    @Transactional
    public void markFailed(UUID documentId, String reason) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        document.setStatus(DocumentStatus.FAILED);
        document.setErrorMessage(truncate(reason));
        document.setStage(null);
        documentRepository.saveAndFlush(document);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "Ingestion failed.";
        }
        return reason.length() > MAX_ERROR_LENGTH ? reason.substring(0, MAX_ERROR_LENGTH) : reason;
    }
}
