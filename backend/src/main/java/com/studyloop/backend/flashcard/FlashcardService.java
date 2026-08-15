package com.studyloop.backend.flashcard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentChunk;
import com.studyloop.backend.document.DocumentChunkRepository;
import com.studyloop.backend.document.DocumentNotFoundException;
import com.studyloop.backend.document.DocumentRepository;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.flashcard.dto.CreateFlashcardRequest;
import com.studyloop.backend.flashcard.dto.FlashcardResponse;
import com.studyloop.backend.flashcard.dto.GenerateFlashcardsRequest;
import com.studyloop.backend.review.ReviewService;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Builds and stores flashcards. Generation grounds the model on one document's text and asks for
// front/back pairs as JSON (structured output). Cards can also be saved by hand (the "save this
// Q&A as a card" flow) or auto-created from a quiz question the owner got wrong. Cards are
// personal: each is owned by its creator and only they list or delete them.
//
// Every card that is created here is also enrolled in the SM-2 review queue (Phase 8.1), so a
// card is never stranded outside the schedule.
@Service
@RequiredArgsConstructor
public class FlashcardService {

    private static final int DEFAULT_COUNT = 10;
    private static final int MATERIAL_CHAR_BUDGET = 12_000;

    // Own ObjectMapper (no bean in this project's modular web stack); tolerate unknown fields.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CourseAccess courseAccess;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ChatClient chatClient;
    private final FlashcardRepository flashcardRepository;
    private final ReviewService reviewService;

    // Generates a deck from one READY document and saves it to the caller's cards.
    @Transactional
    public List<FlashcardResponse> generate(UUID actorId, UUID courseId, GenerateFlashcardsRequest request) {
        Membership member = courseAccess.requireMember(actorId, courseId);
        if (!chatClient.isConfigured()) {
            throw new FlashcardGenerationException("Flashcard generation provider is not configured.");
        }

        Document document = documentRepository.findByIdAndCourseSpaceId(request.documentId(), courseId)
                .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
        if (document.getStatus() != DocumentStatus.READY) {
            throw new NoFlashcardMaterialException("The document has not finished ingesting yet.");
        }

        String material = gatherMaterial(document);
        if (material.isBlank()) {
            throw new NoFlashcardMaterialException("The document has no ingested content to build cards from.");
        }

        int count = request.count() != null ? request.count() : DEFAULT_COUNT;
        List<GeneratedCard> generated = callModel(material, count).stream()
                .filter(FlashcardService::isUsable)
                .toList();
        if (generated.isEmpty()) {
            throw new FlashcardGenerationException("The model did not return any usable cards.");
        }

        List<Flashcard> saved = new ArrayList<>(generated.size());
        for (GeneratedCard source : generated) {
            Flashcard card = new Flashcard();
            card.setCourseSpace(member.getCourseSpace());
            card.setCreatedBy(member.getUser());
            card.setFront(source.front().strip());
            card.setBack(source.back().strip());
            card.setSourceDocumentId(document.getId());
            flashcardRepository.save(card);
            reviewService.enroll(card);
            saved.add(card);
        }
        flashcardRepository.flush();
        return saved.stream().map(FlashcardResponse::from).toList();
    }

    // Saves one card by hand (e.g. from a chat exchange). If a source document is given, it must
    // belong to the same course.
    @Transactional
    public FlashcardResponse create(UUID actorId, UUID courseId, CreateFlashcardRequest request) {
        Membership member = courseAccess.requireMember(actorId, courseId);

        if (request.sourceDocumentId() != null) {
            documentRepository.findByIdAndCourseSpaceId(request.sourceDocumentId(), courseId)
                    .orElseThrow(() -> new DocumentNotFoundException(request.sourceDocumentId()));
        }

        Flashcard card = new Flashcard();
        card.setCourseSpace(member.getCourseSpace());
        card.setCreatedBy(member.getUser());
        card.setFront(request.front().strip());
        card.setBack(request.back().strip());
        card.setSourceDocumentId(request.sourceDocumentId());
        card.setSourcePage(request.sourcePage());
        flashcardRepository.saveAndFlush(card);
        reviewService.enroll(card);
        return FlashcardResponse.from(card);
    }

    // Auto-enrols the questions a user just got wrong as flashcards (Phase 8.1) — the "your
    // mistakes come back tomorrow" half of the loop. Called by quiz grading inside its own
    // transaction. Idempotent: a question that already has a card for this user is skipped, so
    // retaking a quiz and missing the same question again doesn't duplicate it. Returns the cards
    // actually created.
    @Transactional
    public List<Flashcard> enrollMissedQuestions(Membership member, List<MissedQuestion> missed) {
        if (missed.isEmpty()) {
            return List.of();
        }
        List<UUID> questionIds = missed.stream().map(MissedQuestion::questionId).toList();
        Set<UUID> alreadyCarded = flashcardRepository
                .findByCreatedByIdAndSourceQuizQuestionIdIn(member.getUser().getId(), questionIds)
                .stream()
                .map(Flashcard::getSourceQuizQuestionId)
                .collect(Collectors.toSet());

        List<Flashcard> created = new ArrayList<>();
        for (MissedQuestion question : missed) {
            if (alreadyCarded.contains(question.questionId()) || !question.isUsable()) {
                continue;
            }
            Flashcard card = new Flashcard();
            card.setCourseSpace(member.getCourseSpace());
            card.setCreatedBy(member.getUser());
            card.setFront(question.front().strip());
            card.setBack(question.back().strip());
            card.setSourceQuizQuestionId(question.questionId());
            flashcardRepository.save(card);
            reviewService.enroll(card);
            created.add(card);
        }
        flashcardRepository.flush();
        return created;
    }

    // A question the caller answered incorrectly, reduced to card shape. Lives here rather than in
    // the quiz package so the dependency only points one way: quiz → flashcard.
    public record MissedQuestion(UUID questionId, String front, String back) {

        boolean isUsable() {
            return questionId != null
                    && front != null && !front.isBlank()
                    && back != null && !back.isBlank();
        }
    }

    @Transactional(readOnly = true)
    public List<FlashcardResponse> list(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        return flashcardRepository
                .findByCourseSpaceIdAndCreatedByIdOrderByCreatedAtDesc(courseId, actorId)
                .stream()
                .map(FlashcardResponse::from)
                .toList();
    }

    @Transactional
    public void delete(UUID actorId, UUID courseId, UUID cardId) {
        courseAccess.requireMember(actorId, courseId);
        Flashcard card = flashcardRepository
                .findByIdAndCourseSpaceIdAndCreatedById(cardId, courseId, actorId)
                .orElseThrow(() -> new FlashcardNotFoundException(cardId));
        flashcardRepository.delete(card);
    }

    // ── generation internals ────────────────────────────────────────────────────────────────

    private String gatherMaterial(Document document) {
        StringBuilder material = new StringBuilder();
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(document.getId());
        for (DocumentChunk chunk : chunks) {
            if (material.length() >= MATERIAL_CHAR_BUDGET) {
                break;
            }
            material.append(chunk.getContent().strip()).append("\n\n");
        }
        return material.toString();
    }

    private List<GeneratedCard> callModel(String material, int count) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(buildSystemPrompt(count)),
                LlmMessage.user("Document text:\n\n" + material));

        // Labels the call as flashcard generation for the cost dashboard; completeJson itself
        // can't tell which of its four callers it is serving.
        String json;
        try (var ignored = AiUsageContext.of(AiOperation.FLASHCARD_GENERATION)) {
            json = chatClient.completeJson(messages);
        }
        try {
            GeneratedDeck deck = objectMapper.readValue(json, GeneratedDeck.class);
            return deck.cards() == null ? List.of() : deck.cards();
        } catch (JsonProcessingException e) {
            throw new FlashcardGenerationException("The model returned malformed flashcard JSON.", e);
        }
    }

    private static String buildSystemPrompt(int count) {
        return """
                You are StudyLoop's flashcard generator. Using ONLY the document text the user \
                provides, create up to %d study flashcards that capture its key facts and concepts. \
                Do not use outside knowledge.

                Each card has a `front` (a question or term) and a `back` (the concise answer or \
                definition). Keep both sides short and self-contained.

                Return a SINGLE JSON object, nothing else, with this exact shape:
                {"cards":[{"front":"...","back":"..."}]}
                """.formatted(count);
    }

    private static boolean isUsable(GeneratedCard card) {
        return card != null
                && card.front() != null && !card.front().isBlank()
                && card.back() != null && !card.back().isBlank();
    }

    // Model output shapes (parsed from completeJson).
    private record GeneratedDeck(List<GeneratedCard> cards) {
    }

    private record GeneratedCard(String front, String back) {
    }
}
