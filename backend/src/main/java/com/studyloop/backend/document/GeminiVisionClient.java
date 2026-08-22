package com.studyloop.backend.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.config.VisionProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageRecorder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
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

    private static final String JSON_MIME_TYPE = "application/json";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
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
                new GenerationConfig(0.0, null, new ThinkingConfig(0)));

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
        record(response, AiOperation.VLM_EXTRACTION);
        return markdownOf(response);
    }

    // 16.3 — a photograph of somebody's notes, read as blocks with a confidence on each.
    //
    // **JSON out, not Markdown**, because the confidence is the point and it has to survive the
    // trip. Asking for "the Markdown, and also how sure you are" in prose gets a paragraph of
    // hedging appended to the notes; `responseMimeType` makes the API itself enforce a parseable
    // shape, so the number arrives as a number and never lands in the corpus as text.
    //
    // Thinking stays off here too. The model is not being asked to work anything out — it is being
    // asked what the page says, and what it cannot read it should say it cannot read.
    @Override
    public List<TranscribedBlock> readHandwriting(byte[] image, String mimeType) {
        if (!isConfigured()) {
            throw new VisionExtractionException("Google vision API key is not configured.");
        }
        Request request = new Request(
                new Content(List.of(new Part(HANDWRITING_INSTRUCTION, null))),
                List.of(new Content(List.of(new Part(null,
                        new InlineData(mimeType, Base64.getEncoder().encodeToString(image)))))),
                new GenerationConfig(0.0, JSON_MIME_TYPE, new ThinkingConfig(0)));

        JsonNode response;
        try {
            response = restClient.post()
                    .uri(ENDPOINT, model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new VisionExtractionException(
                    "Gemini handwriting request failed: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new VisionExtractionException("Gemini returned an empty handwriting response.");
        }
        record(response, AiOperation.HANDWRITING_OCR);
        return blocksOf(markdownOf(response));
    }

    // The model's JSON, turned into blocks. Deliberately forgiving about the wrapper and strict
    // about the contents: a model that answers `{"blocks": [...]}` instead of `[...]` has still
    // done the job, but a block with no text is not a block, and a missing confidence is read as
    // zero rather than as one — an unstated confidence is not a high one.
    private List<TranscribedBlock> blocksOf(String json) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(stripFence(json));
        } catch (JsonProcessingException e) {
            throw new VisionExtractionException(
                    "Gemini did not return readable JSON for the note image.", e);
        }
        JsonNode array = parsed.isArray() ? parsed : parsed.path("blocks");
        if (!array.isArray()) {
            throw new VisionExtractionException("Gemini returned no blocks for the note image.");
        }
        List<TranscribedBlock> blocks = new ArrayList<>();
        for (JsonNode node : array) {
            String content = node.path("content").asText("").strip();
            if (content.isEmpty()) {
                continue;
            }
            double confidence = clamp(node.path("confidence").asDouble(0.0));
            // `indexed` is not this class's decision — the threshold lives in configuration and
            // the extractor applies it. Every block leaves here as "read, not yet judged".
            blocks.add(new TranscribedBlock(blocks.size(), content, confidence, false));
        }
        if (blocks.isEmpty()) {
            throw new VisionExtractionException(
                    "Gemini read nothing from the note image. If the photo is blurred or the page "
                            + "is blank, there is nothing to index.");
        }
        return blocks;
    }

    // `responseMimeType` makes a fenced code block unlikely rather than impossible, and a stray
    // ```json wrapper would otherwise fail a whole upload over three backticks.
    private static String stripFence(String json) {
        String trimmed = json.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).strip();
    }

    private static double clamp(double confidence) {
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    // Tokens land on the uploader's ledger and budget, because the ingestion listener names them
    // as the actor for the length of the pipeline (Phase 15.3). A model with no price entry is
    // still recorded — the call count stays true even when the money does not.
    private void record(JsonNode response, AiOperation operation) {
        JsonNode usage = response.get("usageMetadata");
        int input = usage == null ? 0 : usage.path("promptTokenCount").asInt(0);
        // thoughtsTokenCount is billed as output and is separate from candidatesTokenCount. It is
        // zero while thinking is off, and adding it anyway means the ledger stays honest the day
        // somebody turns thinking back on.
        int output = usage == null ? 0
                : usage.path("candidatesTokenCount").asInt(0) + usage.path("thoughtsTokenCount").asInt(0);
        usageRecorder.record(PROVIDER, model, operation, input, output);
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

    // The handwriting prompt. Each of its rules is here because of a specific way this goes wrong.
    //
    // **Blocks, not one string**, because confidence is not a property of a page: a note is usually
    // legible prose with two unreadable lines in it, and one number for the whole photo either
    // throws the page away or keeps the guesses.
    //
    // **`$...$` rather than a LaTeX document**, per 16.3. LaTeX is a rendering target; the chunker
    // and the tsvector want prose with the maths inline, and 11.2's KaTeX already renders `$...$`
    // in the answer view. Notes stored as LaTeX would have to be un-typeset before they could be
    // indexed, whereas the export in the other direction is a converter and loses nothing.
    //
    // **Say [illegible] rather than guess**, which is the page reader's rule and matters more here.
    // A misread word in a typeset page is rare; in handwriting it is the normal failure, and a
    // plausible guess is indistinguishable from a reading until somebody revises from it.
    private static final String HANDWRITING_INSTRUCTION = """
            You are StudyLoop's notes reader. You are given a photograph of one page of \
            handwritten study notes. Return what is written on it, split into blocks.

            Answer with a JSON array. Each element is an object with exactly two fields:
              "content"    - one block of the page as GitHub-flavoured Markdown
              "confidence" - a number from 0 to 1: how sure you are that you read this block \
            correctly. Be honest and be harsh. 1.0 means every character is unambiguous.

            A block is a heading, a paragraph, a list, a table, an equation, or a described \
            diagram - the units a reader would see on the page, in the order they appear.

            Rules for "content":
            - Headings as `#`/`##`, following the page's own hierarchy.
            - Maths inline between single dollar signs, display maths between double dollar \
            signs. Do not return a LaTeX document, only the maths itself.
            - Tables as Markdown tables. Bullet and numbered lists as Markdown lists.
            - A diagram or sketch becomes a short paragraph describing what it shows and what \
            its parts are labelled, so that somebody searching for those words finds this note.
            - Transcribe only what is written. Never finish a sentence the page does not finish. \
            Where you cannot read a word, write [illegible] and lower that block's confidence.
            - No preamble, and no commentary about the photograph itself.
            """;

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
    private record GenerationConfig(Double temperature, String responseMimeType,
                                    ThinkingConfig thinkingConfig) { }

    private record ThinkingConfig(int thinkingBudget) { }
}
