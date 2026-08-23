package com.studyloop.backend.document;

import com.studyloop.backend.chat.SemanticCacheService;
import com.studyloop.backend.config.SummaryProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// Orchestrates the ingestion pipeline for one document, driving its status forward and
// recording a FAILED status if any step throws. Deliberately not @Async or @Transactional
// itself: the async trigger lives in DocumentIngestionListener, and each status write is
// its own transaction (via DocumentStatusService) so progress is observable step by step.
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final DocumentRepository documentRepository;
    private final DocumentStorageService storageService;
    private final DocumentStatusService statusService;
    private final DocumentExtractors extractors;
    private final DocumentNoteBlockService noteBlockService;
    private final TextChunker textChunker;
    private final VisualChunker visualChunker;
    private final SyntheticQueryGenerator syntheticQueryGenerator;
    private final DocumentChunkService chunkService;
    private final DocumentEmbeddingService embeddingService;
    private final DocumentSummaryService summaryService;
    private final SummaryProperties summaryProperties;
    private final SemanticCacheService semanticCache;

    public void ingest(UUID documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            // The document was removed (e.g. its course was deleted) before ingestion ran.
            return;
        }
        String storagePath = document.getStoragePath();
        UUID courseId = document.getCourseSpace().getId();

        try {
            statusService.markStatus(documentId, DocumentStatus.EXTRACTING);
            byte[] bytes = storageService.read(storagePath);
            // Phase 15 scored a PDF's pages and sent the failures to a vision model; Phase 16 made
            // that one of four routes, chosen by the document's stored content type. What comes
            // back is the same either way — Markdown pages numbered from one — which is why a
            // slide deck, a Word document and a photograph of a notebook all reach the rest of
            // this method as the thing it already knew how to ingest, and why nothing below here
            // asks what the file was.
            Extraction extraction = extractors.extract(
                    document.getContentType(), document.getFilename(), bytes);
            List<PageText> pages = extraction.pages();

            statusService.markStatus(documentId, DocumentStatus.CHUNKING);
            String title = titleOf(document);
            List<TextChunk> chunks = textChunker.chunk(pages, title);
            // Phase 14.1, and deliberately before the chunks are written rather than after. What
            // it produces goes into embed_text, which is the string the next step embeds and which
            // content_tsv is generated from — so a second pass would mean re-embedding every chunk
            // in the document to index text that could have been there the first time. Off by
            // default, and a no-op that returns the same list when it is.
            chunks = syntheticQueryGenerator.augment(chunks);
            // 17.2, and after the text chunks rather than beside them, because a visual chunk
            // continues the same index sequence. An extractor that rendered no pages produces an
            // empty list here and the rest of this method reads exactly as it did in Phase 16.
            List<VisualChunk> visuals =
                    visualChunker.chunk(extraction.images(), pages, title, chunks.size());
            chunkService.replaceChunks(documentId, chunks, visuals, pages.size(),
                    extraction.visionPages());
            // 16.3, and only ever non-empty for a photographed note. The blocks the model was not
            // sure about are not in `chunks` — that is the point of the threshold — so this is the
            // only record that they were on the page at all, and the review view reads it.
            noteBlockService.replaceBlocks(documentId, extraction.blocks());

            statusService.markStatus(documentId, DocumentStatus.EMBEDDING);
            embeddingService.embedChunks(documentId);
            // Two calls rather than one, because the two are embedding different things: the text
            // of a passage, and a picture of a page. Same model, same space, same column — which
            // is exactly why they can be two calls and still be compared against one query.
            embeddingService.embedVisualChunks(documentId, visuals);

            statusService.markStatus(documentId, DocumentStatus.READY);
        } catch (Exception e) {
            statusService.markFailed(documentId, e.getMessage());
            return;
        }

        // The course's corpus just grew, so every answer cached against the old one is suspect —
        // most of all the refusals, which this document may well have just made answerable.
        semanticCache.invalidate(courseId);
        summarizeQuietly(courseId, documentId);
    }

    // Half of a chunk's context header (13.4), and the only half a document with no headings has.
    // The filename is what the uploader called the material — "Lecture 07 - Hashing.pdf" — so the
    // extension and the separators come off and the words go into the indexed text.
    private static String titleOf(Document document) {
        String filename = document.getFilename();
        if (filename == null || filename.isBlank()) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        return stem.replaceAll("[_-]+", " ").replaceAll("\\s+", " ").strip();
    }

    // Phase 8.2, deliberately after READY and deliberately swallowing its failure: the summary is
    // a convenience built on top of a document that is already fully usable for chat, quizzes and
    // flashcards. A provider outage must not retroactively mark that document FAILED. The summary
    // stays null and any member can trigger generation later from the document view.
    private void summarizeQuietly(UUID courseId, UUID documentId) {
        if (!summaryProperties.autoGenerate()) {
            return;
        }
        try {
            if (summaryService.generate(courseId, documentId, false)) {
                log.info("Summarized document {}", documentId);
            }
        } catch (Exception e) {
            log.warn("Could not summarize document {}: {}", documentId, e.getMessage());
        }
    }
}
