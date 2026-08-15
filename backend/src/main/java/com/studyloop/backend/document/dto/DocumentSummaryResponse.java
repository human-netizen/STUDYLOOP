package com.studyloop.backend.document.dto;

import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.DocumentTerm;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A document's cached summary and glossary. `summary` is null and `terms` empty when nothing has
// been generated yet — the client shows a "generate" affordance rather than an error, since a
// missing summary is an ordinary state (old documents, or a provider that was down at ingestion).
public record DocumentSummaryResponse(
        UUID documentId,
        String filename,
        DocumentStatus status,
        String summary,
        List<GlossaryTerm> terms,
        Instant generatedAt
) {

    public static DocumentSummaryResponse from(Document document, List<DocumentTerm> terms) {
        return new DocumentSummaryResponse(
                document.getId(),
                document.getFilename(),
                document.getStatus(),
                document.getSummary(),
                terms.stream().map(GlossaryTerm::from).toList(),
                document.getSummaryGeneratedAt());
    }

    public record GlossaryTerm(String term, String definition) {

        static GlossaryTerm from(DocumentTerm term) {
            return new GlossaryTerm(term.getTerm(), term.getDefinition());
        }
    }
}
