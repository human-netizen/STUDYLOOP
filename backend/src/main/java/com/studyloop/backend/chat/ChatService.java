package com.studyloop.backend.chat;

import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.chat.dto.ChatResponse;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.config.ChatProperties;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.retrieval.RetrievalResult;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// The RAG orchestrator: retrieve the most relevant chunks for a question, ground the model on
// them, and persist the turn so follow-ups have context. The model is told to answer only from
// the numbered sources and to cite them inline as [n], which the response maps back to sources.
@Service
@RequiredArgsConstructor
public class ChatService {

    // How many chunks to ground on — matches the retrieval default and keeps the prompt small.
    private static final int RETRIEVAL_K = 6;
    // Cap replayed history so a long thread can't blow the prompt budget (last N turns only).
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final int MAX_TITLE_LENGTH = 200;
    // Shown verbatim when the confidence gate trips — a deterministic refusal, no model call.
    private static final String NOT_IN_MATERIALS =
            "I don't have that in this course's materials. Try rephrasing, or upload a document "
            + "that covers it.";

    private final CourseAccess courseAccess;
    private final RetrievalService retrievalService;
    private final ChatClient chatClient;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatProperties chatProperties;

    @Transactional
    public ChatResponse chat(UUID actorId, UUID courseId, ChatRequest request) {
        Membership member = courseAccess.requireMember(actorId, courseId);
        if (!chatClient.isConfigured()) {
            throw new ChatException("Chat provider is not configured.");
        }

        String question = request.question().trim();
        ChatConversation conversation = resolveConversation(request.conversationId(), courseId, actorId, member, question);

        // Ground on the course's materials (reuses the same hybrid retrieval as the API).
        RetrievalResult retrieval = retrievalService.search(actorId, courseId, question, RETRIEVAL_K);
        List<RetrievedChunk> chunks = retrieval.chunks();

        // Confidence gate: if nothing relevant came back, refuse deterministically instead of
        // letting the model answer from weak or absent context (and skip the provider call).
        if (shouldRefuse(retrieval)) {
            saveMessage(conversation, ChatRole.USER, question);
            saveMessage(conversation, ChatRole.ASSISTANT, NOT_IN_MATERIALS);
            return new ChatResponse(conversation.getId(), NOT_IN_MATERIALS, List.of());
        }

        // system prompt (with numbered sources) → prior turns → the new question.
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt(chunks)));
        messages.addAll(replayHistory(conversation.getId()));
        messages.add(LlmMessage.user(question));

        String answer = chatClient.complete(messages);

        // Persist both turns only after a successful answer, so a provider failure leaves no
        // half-written conversation.
        saveMessage(conversation, ChatRole.USER, question);
        saveMessage(conversation, ChatRole.ASSISTANT, answer);

        return new ChatResponse(conversation.getId(), answer, toCitations(chunks));
    }

    // Refuse when there's simply nothing to answer from, or when the semantic match is too weak
    // AND no chunk matched the query's words either. Keeping the lexical escape hatch means an
    // exact-keyword question still answers even if its embedding sits below the threshold; only
    // genuinely off-topic questions (weak on both signals) get the canned refusal. A threshold
    // of 0 disables the semantic half of the gate, leaving only the empty-result case.
    private boolean shouldRefuse(RetrievalResult retrieval) {
        if (retrieval.chunks().isEmpty()) {
            return true;
        }
        double threshold = chatProperties.minSimilarity();
        if (threshold <= 0) {
            return false;
        }
        boolean weakSemantic = retrieval.topVectorSimilarity().isPresent()
                && retrieval.topVectorSimilarity().getAsDouble() < threshold;
        boolean noLexical = retrieval.lexicalHitCount() == 0;
        return weakSemantic && noLexical;
    }

    private ChatConversation resolveConversation(UUID conversationId, UUID courseId, UUID actorId,
                                                 Membership member, String question) {
        if (conversationId != null) {
            return conversationRepository
                    .findByIdAndCourseSpaceIdAndCreatedById(conversationId, courseId, actorId)
                    .orElseThrow(() -> new ChatConversationNotFoundException(conversationId));
        }
        ChatConversation conversation = new ChatConversation();
        conversation.setCourseSpace(member.getCourseSpace());
        conversation.setCreatedBy(member.getUser());
        conversation.setTitle(titleFrom(question));
        // Flush so the generated id is available for the messages saved below.
        return conversationRepository.saveAndFlush(conversation);
    }

    // Prior turns, oldest-first, capped to the most recent MAX_HISTORY_MESSAGES.
    private List<LlmMessage> replayHistory(UUID conversationId) {
        List<ChatMessage> all = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<ChatMessage> recent = all.size() > MAX_HISTORY_MESSAGES
                ? all.subList(all.size() - MAX_HISTORY_MESSAGES, all.size())
                : all;
        List<LlmMessage> history = new ArrayList<>(recent.size());
        for (ChatMessage message : recent) {
            history.add(message.getRole() == ChatRole.USER
                    ? LlmMessage.user(message.getContent())
                    : LlmMessage.assistant(message.getContent()));
        }
        return history;
    }

    private void saveMessage(ChatConversation conversation, ChatRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content);
        messageRepository.save(message);
    }

    private static String buildSystemPrompt(List<RetrievedChunk> chunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are StudyLoop's study assistant. Answer the student's question using ONLY the \
                numbered sources below.
                - Cite every claim with its source number in square brackets, e.g. [1] or [2][3].
                - If the sources do not contain the answer, say you don't have that in the course \
                materials. Do not use outside knowledge or guess.
                - Be concise and precise.

                Sources:
                """);
        if (chunks.isEmpty()) {
            prompt.append("(no relevant sources were found in the course materials)");
            return prompt.toString();
        }
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            prompt.append('[').append(i + 1).append("] (").append(chunk.filename());
            if (chunk.pageNumber() != null) {
                prompt.append(", p.").append(chunk.pageNumber());
            }
            prompt.append(")\n").append(chunk.content().strip()).append("\n\n");
        }
        return prompt.toString();
    }

    private static List<Citation> toCitations(List<RetrievedChunk> chunks) {
        List<Citation> citations = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            citations.add(Citation.from(i + 1, chunks.get(i)));
        }
        return citations;
    }

    private static String titleFrom(String question) {
        String oneLine = question.replaceAll("\\s+", " ").strip();
        return oneLine.length() > MAX_TITLE_LENGTH
                ? oneLine.substring(0, MAX_TITLE_LENGTH)
                : oneLine;
    }
}
