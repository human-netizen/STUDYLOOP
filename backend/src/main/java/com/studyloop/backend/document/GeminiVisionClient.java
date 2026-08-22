package com.studyloop.backend.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.studyloop.backend.config.VisionProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageRecorder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Base64;
import java.util.List;

// Reads a page image with Gemini Flash and returns Markdown (Phase 15.2).
//
// **A second provider, and it is not a preference.** Cohere — which does embeddings, chat and
// reranking here — has no vision model, so a phase about reading pictures has to go somewhere else.
// Google is the cheapest place with an existing foothold in this codebase: `GOOGLE_API_KEY` and
// GoogleEmbeddingClient already exist, so the key, the host and the request idiom are all in place.
//
// **Why a VLM rather than OCR.** Tesseract solves exactly one of the four defects the gate finds —
// a scan — and reproduces the other three. It has the same reading-order failure on a two-column
// page that PDFBox has, it emits plain text so a table comes back as ragged columns and an equation
// as punctuation, and it cannot describe a figure at all. A vision model reads the page the way a
// person does: it knows a two-column layout is two columns, that a grid of numbers is a table, and
// that the picture is a plot of two functions.
//
// Thinking is switched off explicitly. Gemini 2.5 models reason before answering by default, which
// is billed as output tokens and is worth paying for on a hard question; transcribing a page that
// is in front of the model is not one. Left on, it roughly doubles the output bill of the phase for
// no change in what comes back.
@Component
public class GeminiVisionClient implements VisionClient {

    private static final String PROVIDER = "google";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}";
    private static final String MIME_TYPE = "image/png";

    private final RestClient restClient = RestClient.create();
    private final AiUsageRecorder usageRecorder;
    private final String apiKey;
    private final String model;

    public GeminiVisionClient(VisionProperties properties, AiUsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder;
        this.apiKey = properties.apiKey();
        this.model = properties.model();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String readPage(byte[] pngImage, PageDefect hint) {
        if (!isConfigured()) {
            throw new VisionExtractionException("Google vision API key is not configured.");
        }
        Request request = new Request(
                new Content(List.of(new Part(instruction(hint), null))),
                List.of(new Content(List.of(
                        new Part(null, new InlineData(MIME_TYPE, Base64.getEncoder().encodeToString(pngImage)))))),
                new GenerationConfig(0.0, new ThinkingConfig(0)));

        JsonNode response;
        try {
            response = restClient.post()
                    .uri(ENDPOINT, model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new VisionExtractionException("Gemini vision request failed: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new VisionExtractionException("Gemini returned an empty vision response.");
        }
        record(response);
        return markdownOf(response);
    }

    // Tokens land on the uploader's ledger and budget, because the ingestion listener names them
    // as the actor for the length of the pipeline (Phase 15.3). A model with no price entry is
    // still recorded — the call count stays true even when the money does not.
    private void record(JsonNode response) {
        JsonNode usage = response.get("usageMetadata");
        int input = usage == null ? 0 : usage.path("promptTokenCount").asInt(0);
        // thoughtsTokenCount is billed as output and is separate from candidatesTokenCount. It is
        // zero while thinking is off, and adding it anyway means the ledger stays honest the day
        // somebody turns thinking back on.
        int output = usage == null ? 0
                : usage.path("candidatesTokenCount").asInt(0) + usage.path("thoughtsTokenCount").asInt(0);
        usageRecorder.record(PROVIDER, model, AiOperation.VLM_EXTRACTION, input, output);
    }

    // The page's Markdown, joined across parts. A refusal or a safety block arrives as a candidate
    // with no content rather than as an HTTP error, so an empty read is raised here rather than
    // returned as an empty page — an empty page would replace real text with nothing.
    private static String markdownOf(JsonNode response) {
        JsonNode candidates = response.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            throw new VisionExtractionException("Gemini returned no candidate for the page"
                    + finishReason(response));
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        StringBuilder markdown = new StringBuilder();
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                markdown.append(text);
            }
        }
        String read = markdown.toString().strip();
        if (read.isEmpty()) {
            throw new VisionExtractionException("Gemini read no text from the page"
                    + finishReason(response));
        }
        return read;
    }

    private static String finishReason(JsonNode response) {
        String reason = response.path("candidates").path(0).path("finishReason").asText("");
        if (reason.isBlank()) {
            reason = response.path("promptFeedback").path("blockReason").asText("");
        }
        return reason.isBlank() ? "." : " (" + reason + ").";
    }

    // What the model is told to do with the page, varied by what the gate found wrong with it.
    //
    // The shared half is the contract with the rest of the pipeline: Markdown out, no commentary,
    // and nothing invented. "Nothing invented" is the load-bearing line — this text is indexed as
    // course material and cited to a student, so a model filling a blurred word with a plausible
    // one is writing the textbook.
    private static String instruction(PageDefect hint) {
        return """
                You are StudyLoop's page reader. You are given an image of one page of course \
                material. Return that page's content as GitHub-flavoured Markdown.

                Rules:
                - Headings as `#`/`##`, in the same hierarchy the page shows.
                - Tables as Markdown tables. Equations as LaTeX between $ signs.
                - Code and pseudocode in fenced blocks.
                - Transcribe only what is on the page. Never complete a sentence the page does not \
                finish, and never supply a word you cannot read — write [illegible] instead.
                - No preamble, no commentary, no description of the page as a page. Output the \
                content and nothing else.
                - Running heads, footers and bare page numbers are furniture. Leave them out.
                %s""".formatted(extraFor(hint));
    }

    private static String extraFor(PageDefect hint) {
        if (hint == null) {
            return "";
        }
        return switch (hint) {
            // Nothing was extractable, so everything has to come from the image.
            case SCANNED -> "- This page is a scan. Transcribe all of its text.\n";
            // The characters in the file are wrong, so the model must read the glyphs it sees and
            // must not be helped by anything that looks like it came from the document.
            case BROKEN_ENCODING -> "- This page's embedded text is corrupt. Read the glyphs as "
                    + "rendered and ignore any character that is plainly not a letter of the "
                    + "language the page is written in.\n";
            // The words are right and their order is not, which is the whole reason this page is
            // here: reading order is the thing being bought.
            case UNRELIABLE_ORDER -> "- This page has a multi-column or otherwise non-linear "
                    + "layout. Return the text in human reading order, one column fully before "
                    + "the next.\n";
            // The text extracted fine and the picture did not. The description is what makes the
            // figure findable, so it has to be in the words a student would search for.
            case FIGURE -> "- This page contains a figure, diagram or chart. After transcribing "
                    + "the text, describe the figure in two or three sentences: what it plots or "
                    + "shows, what its axes or parts are labelled, and what it demonstrates. Name "
                    + "the concepts, so somebody searching for them finds this page.\n";
        };
    }

    // ── request shapes for the Generative Language API (responses are read as JsonNode) ─────────

    private record Request(Content systemInstruction, List<Content> contents,
                           GenerationConfig generationConfig) { }

    private record Content(List<Part> parts) { }

    // Exactly one of the two is ever set: a part is text or it is an image. NON_NULL is required
    // rather than tidy — the API rejects a part carrying an explicit `"inlineData": null`.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Part(String text, InlineData inlineData) { }

    private record InlineData(String mimeType, String data) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GenerationConfig(Double temperature, ThinkingConfig thinkingConfig) { }

    private record ThinkingConfig(int thinkingBudget) { }
}
