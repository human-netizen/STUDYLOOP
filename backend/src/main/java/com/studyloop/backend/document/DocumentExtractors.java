package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// Picks the extractor for a document's stored content type (Phase 16).
//
// Spring injects every DocumentExtractor bean, so adding a format is adding one class — nothing
// here changes, and nothing in DocumentIngestionService changes either. That matters more than it
// looks: the orchestrator is the class that drives the status machine and decides what a failure
// means, and every format added by editing it is a chance to get that wrong for one format only.
//
// The content type is the *stored* one, which DocumentService normalised at upload from
// DocumentFormat rather than copying out of the request. A file whose browser called it
// `application/octet-stream` is therefore routed by the same string a correctly-typed one is.
@Component
@RequiredArgsConstructor
public class DocumentExtractors {

    private final List<DocumentExtractor> extractors;

    public Extraction extract(String contentType, String filename, byte[] bytes) {
        DocumentFormat format = DocumentFormat.of(contentType, filename)
                .orElseThrow(() -> new UnsupportedDocumentTypeException(
                        "No extractor handles " + contentType + "."));
        for (DocumentExtractor extractor : extractors) {
            if (extractor.supports(format)) {
                return extractor.extract(bytes);
            }
        }
        // Reachable only by adding a DocumentFormat constant and forgetting the extractor, which
        // is a wiring mistake rather than a bad upload — but it happens during ingestion, so it
        // still has to arrive as a sentence on a FAILED document rather than as a stack trace.
        throw new UnsupportedDocumentTypeException(
                "No extractor is registered for " + format.name().toLowerCase() + " files.");
    }
}
