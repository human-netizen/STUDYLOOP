package com.studyloop.backend.quiz;

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
import com.studyloop.backend.quiz.dto.GenerateQuizRequest;
import com.studyloop.backend.quiz.dto.QuizResponse;
import com.studyloop.backend.quiz.dto.QuizResponse.QuizQuestionView;
import com.studyloop.backend.quiz.dto.QuizSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// Generates quizzes from a course's own documents and serves them for taking. Generation grounds
// the model on the selected material and asks for a strict JSON shape (structured output), which
// is parsed, validated, and persisted as questions + options. The take view deliberately omits
// the answer key and explanations — those surface only after grading (Phase 7.2).
@Service
@RequiredArgsConstructor
public class QuizService {

    // Defaults when the request leaves a count null (a small, balanced quiz).
    private static final int DEFAULT_MC = 5;
    private static final int DEFAULT_SA = 2;
    // Cap on how much source text we feed the model, so a large corpus can't blow the prompt.
    private static final int MATERIAL_CHAR_BUDGET = 12_000;
    private static final int MAX_TITLE_LENGTH = 200;

    // This project's modular Boot 4.1 web starter publishes no ObjectMapper bean, so — like
    // CohereChatClient — we keep our own. Unknown fields are ignored so extra keys the model may
    // add to a question don't fail the whole parse.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CourseAccess courseAccess;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ChatClient chatClient;
    private final QuizRepository quizRepository;
    private final QuizQuestionRepository questionRepository;
    private final QuizQuestionOptionRepository optionRepository;

    // Builds a quiz from the chosen documents (or the whole course when none are named) and
    // persists it. Any course member may generate one; the quiz is shared with the course.
    @Transactional
    public QuizResponse generate(UUID actorId, UUID courseId, GenerateQuizRequest request) {
        Membership member = courseAccess.requireMember(actorId, courseId);
        if (!chatClient.isConfigured()) {
            throw new QuizGenerationException("Quiz generation provider is not configured.");
        }

        List<Document> documents = resolveDocuments(courseId, request.documentIds());
        String material = gatherMaterial(documents);
        if (material.isBlank()) {
            throw new NoQuizMaterialException(
                    "The selected documents have no ingested content to quiz on yet.");
        }

        int mcCount = request.multipleChoiceCount() != null ? request.multipleChoiceCount() : DEFAULT_MC;
        int saCount = request.shortAnswerCount() != null ? request.shortAnswerCount() : DEFAULT_SA;
        if (mcCount + saCount == 0) {
            mcCount = DEFAULT_MC;
            saCount = DEFAULT_SA;
        }

        List<GeneratedQuestion> generated = callModel(material, mcCount, saCount);
        List<GeneratedQuestion> valid = generated.stream().filter(QuizService::isUsable).toList();
        if (valid.isEmpty()) {
            throw new QuizGenerationException("The model did not return any usable questions.");
        }

        Quiz quiz = new Quiz();
        quiz.setCourseSpace(member.getCourseSpace());
        quiz.setCreatedBy(member.getUser());
        quiz.setTitle(resolveTitle(request.title(), documents));
        quizRepository.saveAndFlush(quiz);

        List<QuizQuestion> questions = persistQuestions(quiz, valid);
        return toTakeView(quiz, questions);
    }

    @Transactional(readOnly = true)
    public List<QuizSummaryResponse> list(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        return quizRepository.findByCourseSpaceIdOrderByCreatedAtDesc(courseId).stream()
                .map(quiz -> new QuizSummaryResponse(
                        quiz.getId(),
                        quiz.getTitle(),
                        (int) questionRepository.countByQuizId(quiz.getId()),
                        quiz.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public QuizResponse getForTaking(UUID actorId, UUID courseId, UUID quizId) {
        courseAccess.requireMember(actorId, courseId);
        Quiz quiz = requireQuiz(courseId, quizId);
        List<QuizQuestion> questions = questionRepository.findByQuizIdOrderByQuestionIndex(quizId);
        return toTakeView(quiz, questions);
    }

    // Loads a quiz scoped to the course, 404 if it belongs to another course or doesn't exist.
    Quiz requireQuiz(UUID courseId, UUID quizId) {
        return quizRepository.findByIdAndCourseSpaceId(quizId, courseId)
                .orElseThrow(() -> new QuizNotFoundException(quizId));
    }

    // ── generation internals ────────────────────────────────────────────────────────────────

    // Named documents (each scoped to the course, 404 if foreign) or, when none are named, every
    // READY document in the course. Only READY documents have chunks to quiz on.
    private List<Document> resolveDocuments(UUID courseId, List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return documentRepository.findByCourseSpaceIdOrderByCreatedAtDesc(courseId).stream()
                    .filter(document -> document.getStatus() == DocumentStatus.READY)
                    .toList();
        }
        List<Document> documents = new ArrayList<>(documentIds.size());
        for (UUID id : documentIds) {
            Document document = documentRepository.findByIdAndCourseSpaceId(id, courseId)
                    .orElseThrow(() -> new DocumentNotFoundException(id));
            if (document.getStatus() == DocumentStatus.READY) {
                documents.add(document);
            }
        }
        return documents;
    }

    // Concatenates the documents' chunk text up to the character budget, keeping page markers so
    // questions can reference concrete material. Stops as soon as the budget is reached.
    private String gatherMaterial(List<Document> documents) {
        StringBuilder material = new StringBuilder();
        for (Document document : documents) {
            List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(document.getId());
            for (DocumentChunk chunk : chunks) {
                if (material.length() >= MATERIAL_CHAR_BUDGET) {
                    return material.toString();
                }
                material.append("(").append(document.getFilename());
                if (chunk.getPageNumber() != null) {
                    material.append(", p.").append(chunk.getPageNumber());
                }
                material.append(")\n").append(chunk.getContent().strip()).append("\n\n");
            }
        }
        return material.toString();
    }

    private List<GeneratedQuestion> callModel(String material, int mcCount, int saCount) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(buildSystemPrompt(mcCount, saCount)),
                LlmMessage.user("Course material:\n\n" + material));

        String json = chatClient.completeJson(messages);
        try {
            GeneratedQuiz quiz = objectMapper.readValue(json, GeneratedQuiz.class);
            return quiz.questions() == null ? List.of() : quiz.questions();
        } catch (JsonProcessingException e) {
            throw new QuizGenerationException("The model returned malformed quiz JSON.", e);
        }
    }

    private static String buildSystemPrompt(int mcCount, int saCount) {
        return """
                You are StudyLoop's quiz generator. Using ONLY the course material the user \
                provides, write exam questions that test understanding of that material. Do not \
                use outside knowledge and do not ask about anything absent from the material.

                Produce exactly %d multiple-choice question(s) and %d short-answer question(s).

                Return a SINGLE JSON object, nothing else, with this exact shape:
                {
                  "questions": [
                    {
                      "type": "multiple_choice",
                      "prompt": "the question text",
                      "options": ["option A", "option B", "option C", "option D"],
                      "correctOptionIndex": 0,
                      "explanation": "why the correct option is right, grounded in the material"
                    },
                    {
                      "type": "short_answer",
                      "prompt": "the question text",
                      "expectedAnswer": "a concise model answer",
                      "explanation": "what a correct answer must contain"
                    }
                  ]
                }

                Rules:
                - Every multiple_choice question must have exactly 4 options and a \
                correctOptionIndex between 0 and 3.
                - Exactly one option is correct; the others must be plausible but wrong.
                - short_answer questions have no options; give a concise expectedAnswer.
                - Keep prompts self-contained (no "according to the text above").
                """.formatted(mcCount, saCount);
    }

    // A question is usable if it has a prompt and the fields its type requires.
    private static boolean isUsable(GeneratedQuestion question) {
        if (question == null || question.prompt() == null || question.prompt().isBlank()) {
            return false;
        }
        if (question.isMultipleChoice()) {
            List<String> options = question.options();
            return options != null && options.size() >= 2
                    && question.correctOptionIndex() != null
                    && question.correctOptionIndex() >= 0
                    && question.correctOptionIndex() < options.size();
        }
        return question.expectedAnswer() != null && !question.expectedAnswer().isBlank();
    }

    private List<QuizQuestion> persistQuestions(Quiz quiz, List<GeneratedQuestion> generated) {
        List<QuizQuestion> saved = new ArrayList<>(generated.size());
        for (int i = 0; i < generated.size(); i++) {
            GeneratedQuestion source = generated.get(i);
            QuizQuestion question = new QuizQuestion();
            question.setQuiz(quiz);
            question.setQuestionIndex(i);
            question.setPrompt(source.prompt().strip());
            question.setExplanation(source.explanation() == null ? null : source.explanation().strip());

            if (source.isMultipleChoice()) {
                question.setType(QuestionType.MULTIPLE_CHOICE);
                question.setCorrectOptionIndex(source.correctOptionIndex());
                questionRepository.save(question);
                persistOptions(question, source.options());
            } else {
                question.setType(QuestionType.SHORT_ANSWER);
                question.setExpectedAnswer(source.expectedAnswer().strip());
                questionRepository.save(question);
            }
            saved.add(question);
        }
        questionRepository.flush();
        return saved;
    }

    private void persistOptions(QuizQuestion question, List<String> options) {
        for (int i = 0; i < options.size(); i++) {
            QuizQuestionOption option = new QuizQuestionOption();
            option.setQuestion(question);
            option.setOptionIndex(i);
            option.setText(options.get(i).strip());
            optionRepository.save(option);
        }
    }

    // ── view assembly ───────────────────────────────────────────────────────────────────────

    // The answer-free take view: questions in order, each with its choices (empty for short
    // answer). Options for all questions are loaded in one query and grouped by question id.
    private QuizResponse toTakeView(Quiz quiz, List<QuizQuestion> questions) {
        Map<UUID, List<String>> optionsByQuestion = loadOptions(questions);
        List<QuizQuestionView> views = questions.stream()
                .map(question -> new QuizQuestionView(
                        question.getId(),
                        question.getQuestionIndex(),
                        question.getType(),
                        question.getPrompt(),
                        optionsByQuestion.getOrDefault(question.getId(), List.of())))
                .toList();
        return new QuizResponse(quiz.getId(), quiz.getTitle(), quiz.getCreatedAt(), views);
    }

    private Map<UUID, List<String>> loadOptions(List<QuizQuestion> questions) {
        List<UUID> mcIds = questions.stream()
                .filter(question -> question.getType() == QuestionType.MULTIPLE_CHOICE)
                .map(QuizQuestion::getId)
                .toList();
        if (mcIds.isEmpty()) {
            return Map.of();
        }
        return optionRepository.findByQuestionIdInOrderByQuestionIdAscOptionIndexAsc(mcIds).stream()
                .collect(Collectors.groupingBy(
                        option -> option.getQuestion().getId(),
                        Collectors.mapping(QuizQuestionOption::getText, Collectors.toList())));
    }

    private static String resolveTitle(String requested, List<Document> documents) {
        if (requested != null && !requested.isBlank()) {
            String trimmed = requested.strip();
            return trimmed.length() > MAX_TITLE_LENGTH ? trimmed.substring(0, MAX_TITLE_LENGTH) : trimmed;
        }
        if (documents.size() == 1) {
            return "Quiz — " + documents.get(0).getFilename();
        }
        return "Course quiz";
    }

    // ── model output shapes (parsed from completeJson) ──────────────────────────────────────

    private record GeneratedQuiz(List<GeneratedQuestion> questions) {
    }

    private record GeneratedQuestion(
            String type,
            String prompt,
            List<String> options,
            Integer correctOptionIndex,
            String expectedAnswer,
            String explanation) {

        boolean isMultipleChoice() {
            return !"short_answer".equalsIgnoreCase(type);
        }
    }
}
