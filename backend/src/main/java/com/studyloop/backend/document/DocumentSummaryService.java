package com.studyloop.backend.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.document.DocumentSummaryStore.SummaryInput;
import com.studyloop.backend.document.DocumentSummaryStore.TermDraft;
import com.studyloop.backend.document.dto.DocumentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Produces and serves a document's summary + glossary (Phase 8.2).
//
// The whole point is that this runs ONCE per document. Summarizing ships several thousand
// characters of source text to the model, so the result is cached on the document row and read
// back from there forever after; generation is a no-op when a summary already exists unless the
// caller explicitly asks to refresh.
//
// Persistence lives in DocumentSummaryStore, which keeps the seconds-long model call outside any
// open transaction — holding a pooled Supabase connection across it would waste the pool.
@Service
@RequiredArgsConstructor
public class DocumentSummaryService {

    // How much source text the model sees. Matches the quiz generator's budget: enough for a
    // faithful overview of a lecture-sized PDF without an unbounded prompt on a 300-page book.
    private static final int MATERIAL_CHAR_BUDGET = 12_000;
    private static final int MAX_TERMS = 12;
    // Mirrors document_terms.term (varchar(120)); anything longer is a sentence, not a term.
    private static final int MAX_TERM_LENGTH = 120;
    private static final int MAX_DEFINITION_LENGTH = 600;

    // Boot 4.1's modular web starter publishes no ObjectMapper bean, so — as in QuizService — we
    // keep our own. Unknown fields are ignored so extra keys don't fail the parse.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CourseAccess courseAccess;
    private final DocumentSummaryStore store;
    private final ChatClient chatClient;

    // The cached summary, or an empty one. Never calls the model: a GET stays cheap and safe, and
    // a document with no summary yet is an ordinary state the client renders as "not generated"
    // rather than an error.
    public DocumentSummaryResponse get(UUID actorId, UUID courseId, UUID documentId) {
        courseAccess.requireMember(actorId, courseId);
        return store.read(courseId, documentId);
    }

    // On-demand generation for a course member: backfills documents ingested before this feature
    // existed and retries ones whose summary failed. Any member may ask, matching quiz and
    // flashcard generation — the material belongs to the course, not to the uploader.
    public DocumentSummaryResponse generateFor(UUID actorId, UUID courseId, UUID documentId, boolean refresh) {
        courseAccess.requireMember(actorId, courseId);
        generate(courseId, documentId, refresh);
        return store.read(courseId, documentId);
    }

    // The ingestion path — nobody is waiting on a request. Returns whether a summary was actually
    // written, so the caller can tell "already had one" from "made one".
    public boolean generate(UUID courseId, UUID documentId, boolean refresh) {
        SummaryInput input = store.readInput(courseId, documentId, MATERIAL_CHAR_BUDGET);
        if (input.alreadySummarized() && !refresh) {
            return false;
        }
        if (input.material().isBlank()) {
            throw new NoSummaryMaterialException(
                    "This document has no ingested text to summarize yet (status: " + input.status() + ").");
        }
        if (!chatClient.isConfigured()) {
            throw new SummaryGenerationException("The summary provider is not configured.");
        }

        GeneratedSummary generated = callModel(input.material());
        if (generated.summary() == null || generated.summary().isBlank()) {
            throw new SummaryGenerationException("The model returned no summary text.");
        }
        store.persist(documentId, generated.summary().strip(), usableTerms(generated.terms()));
        return true;
    }

    private GeneratedSummary callModel(String material) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.user("Document text:\n\n" + material));

        String json;
        try {
            json = chatClient.completeJson(messages);
        } catch (RuntimeException e) {
            // Whatever the provider threw (transport, rate limit, auth), the caller only needs
            // "the summary is unavailable" — which the handler turns into a 502 rather than the
            // 500 an unrecognised exception would produce.
            throw new SummaryGenerationException("The summary provider failed.", e);
        }
        try {
            return objectMapper.readValue(json, GeneratedSummary.class);
        } catch (JsonProcessingException e) {
            throw new SummaryGenerationException("The model returned malformed summary JSON.", e);
        }
    }

    // Keeps only entries with both halves present, drops repeats of the same term (models like to
    // restate one concept under two labels), and caps the list so the glossary stays scannable.
    private static List<TermDraft> usableTerms(List<GeneratedTerm> generated) {
        if (generated == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<TermDraft> drafts = new ArrayList<>();
        for (GeneratedTerm candidate : generated) {
            if (drafts.size() >= MAX_TERMS) {
                break;
            }
            if (!candidate.isUsable()) {
                continue;
            }
            String term = truncate(candidate.term().strip(), MAX_TERM_LENGTH);
            if (!seen.add(term.toLowerCase())) {
                continue;
            }
            drafts.add(new TermDraft(term, truncate(candidate.definition().strip(), MAX_DEFINITION_LENGTH)));
        }
        return drafts;
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static final String SYSTEM_PROMPT = """
            You are StudyLoop's document summarizer. Using ONLY the document text the user \
            provides, write a study-oriented overview and a glossary of its key terms. Do not use \
            outside knowledge and do not mention anything the document does not cover.

            Return a SINGLE JSON object, nothing else, with this exact shape:
            {
              "summary": "3 to 5 sentences describing what this document covers and the main \
            ideas a student should take from it",
              "terms": [
                { "term": "the term as the document uses it", "definition": "one sentence \
            defining it, drawn from the document" }
              ]
            }

            Rules:
            - The summary is plain prose. No headings, no bullet points, no markdown.
            - Write it for someone deciding whether this document answers their question.
            - Give at most %d terms, most central first. Fewer is fine for a short document.
            - A term is a concept, method, or piece of notation the document relies on — not a \
            proper noun that merely appears in it.
            - Keep each term under %d characters; the explanation belongs in the definition.
            """.formatted(MAX_TERMS, MAX_TERM_LENGTH);

    // ── model output shapes (parsed from completeJson) ──────────────────────────────────────

    record GeneratedSummary(String summary, List<GeneratedTerm> terms) { }

    record GeneratedTerm(String term, String definition) {

        boolean isUsable() {
            return term != null && !term.isBlank() && definition != null && !definition.isBlank();
        }
    }
}
