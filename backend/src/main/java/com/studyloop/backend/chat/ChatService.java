package com.studyloop.backend.chat;

import com.studyloop.backend.analytics.QuestionLogService;
import com.studyloop.backend.chat.PreparedTurn.CacheWrite;
import com.studyloop.backend.chat.SemanticCacheService.CacheProbe;
import com.studyloop.backend.chat.SemanticCacheService.CachedAnswer;
import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.chat.dto.ChatResponse;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.retrieval.RetrievalResult;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import com.studyloop.backend.retrieval.SectionExpander;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// The RAG orchestrator: retrieve the most relevant chunks for a question, ground the model on
// them, and persist the turn so follow-ups have context. The model is told to answer only from
// the numbered sources and to cite them inline as [n], which the response maps back to sources.
//
// A turn passes three gates before it reaches the model, cheapest first: the semantic cache
// (have we answered this already?), then retrieval's confidence gate (is this even in the
// materials?), and only then the provider.
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
    private final ConfidenceGate confidenceGate;
    private final SemanticCacheService semanticCache;
    private final QuestionLogService questionLog;
    private final SectionExpander sectionExpander;

    // The non-streaming turn, kept as the simple fallback to /chat/stream. It shares prepare()
    // and completeTurn() with the streaming path rather than restating them.
    //
    // @Transactional sits here as well as on those two methods on purpose: Spring applies it
    // through a proxy, so the calls below — made on `this` — never reach it. Declaring it here
    // means the whole turn runs in one transaction instead of none. The cost is that the model
    // call happens inside it, which is exactly the thing the streaming path was split up to
    // avoid; this endpoint answers in one shot and has no seam to split at.
    @Transactional
    public ChatResponse chat(UUID actorId, UUID courseId, ChatRequest request) {
        PreparedTurn prepared = prepare(actorId, courseId, request);
        if (prepared.isAnswered()) {
            return new ChatResponse(prepared.conversationId(), prepared.finalAnswer(),
                    prepared.citations(), prepared.questionEventId());
        }

        String answer = chatClient.complete(prepared.messages());
        completeTurn(prepared, answer);
        return new ChatResponse(prepared.conversationId(), answer, prepared.citations(), null);
    }

    // The fast DB half of a streaming turn: resolve the thread, try the cache, retrieve, gate,
    // and persist the user's question. Split from the model call so the stream — which can run
    // for seconds — never holds a pooled Supabase connection open. completeTurn() closes it out.
    @Transactional
    public PreparedTurn prepare(UUID actorId, UUID courseId, ChatRequest request) {
        Membership member = courseAccess.requireMember(actorId, courseId);
        if (!chatClient.isConfigured()) {
            throw new ChatException("Chat provider is not configured.");
        }

        String question = request.question().trim();
        // Only an opening question is cacheable. A follow-up ("what about the second one?") is
        // meaningless without the turns before it, so two threads whose latest questions look
        // alike can still need completely different answers. No conversation id means no thread
        // yet, which is the check — no extra query needed.
        boolean opensThread = request.conversationId() == null;
        ChatConversation conversation =
                resolveConversation(request.conversationId(), courseId, actorId, member, question);

        // Record the question now; the answer turn is saved once the answer exists.
        saveMessage(conversation, ChatRole.USER, question);

        CacheProbe probe = opensThread ? semanticCache.probe(courseId, question) : CacheProbe.unavailable();
        if (probe.isHit()) {
            CachedAnswer cached = probe.hit();
            saveMessage(conversation, ChatRole.ASSISTANT, cached.answer());
            // Still a question the class asked, so it still counts toward confusion analytics.
            // Who paid for the answer is a cost concern, not a teaching one. No retrieval ran, so
            // the lecture attribution comes from the citations stored with the cached answer, and
            // there is no top similarity to report.
            questionLog.recordGrounded(courseId, actorId, question, probe.questionVector(), null,
                    documentIdsOf(cached.citations()));
            return PreparedTurn.answered(conversation.getId(), cached.citations(), cached.answer());
        }

        // Ground on the course's materials (the same hybrid retrieval the search API uses),
        // reusing the embedding the probe just computed so a cache miss costs one embedding call
        // rather than two.
        RetrievalResult retrieval = retrievalService.search(
                actorId, courseId, question, probe.questionVector(), RETRIEVAL_K);
        List<RetrievedChunk> chunks = retrieval.chunks();

        // Whichever half of the turn embedded the question, analytics reuses that vector rather
        // than asking the provider for a third copy of it.
        float[] questionVector =
                probe.questionVector() != null ? probe.questionVector() : retrieval.queryVector();
        Double topSimilarity = retrieval.topVectorSimilarity().isPresent()
                ? retrieval.topVectorSimilarity().getAsDouble()
                : null;

        // Confidence gate: if nothing relevant came back, refuse deterministically instead of
        // letting the model answer from weak or absent context (and skip the provider call).
        if (confidenceGate.shouldRefuse(retrieval)) {
            saveMessage(conversation, ChatRole.ASSISTANT, NOT_IN_MATERIALS);
            // The most valuable row this table gets: a question the corpus could not answer. Its
            // id goes back to the client so the refusal can be escalated to the forum as *this*
            // question rather than as a copy of its text.
            UUID questionEventId =
                    questionLog.recordRefused(courseId, actorId, question, questionVector, topSimilarity);
            return PreparedTurn.refused(conversation.getId(), NOT_IN_MATERIALS, questionEventId);
        }

        // Logged here rather than after generation on purpose: the question was asked and the
        // materials could answer it, both of which are settled facts before the model speaks.
        // Waiting for the answer would lose every turn where the provider then times out.
        questionLog.recordGrounded(courseId, actorId, question, questionVector, topSimilarity,
                chunks.stream().map(RetrievedChunk::documentId).collect(Collectors.toSet()));

        // system prompt (numbered sources) → prior turns, which now end with the question we
        // just saved, so there's no need to append it again.
        //
        // The sources are the retrieved chunks expanded to their sections (13.5): retrieval picked
        // them on precision, and the model reads them with the surrounding paragraph it needs.
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(buildSystemPrompt(chunks, sectionExpander.expand(chunks))));
        messages.addAll(replayHistory(conversation.getId()));

        // Cache only what the cache could ever serve: an opening question, answered from the
        // materials, whose embedding we actually have. Refusals are excluded deliberately —
        // "I don't have that" is the one answer most likely to be wrong tomorrow.
        CacheWrite cacheWrite = opensThread && probe.questionVector() != null
                ? new CacheWrite(courseId, question, probe.questionVector())
                : null;
        return PreparedTurn.answerable(conversation.getId(), toCitations(chunks), messages, cacheWrite);
    }

    // Saves the finished answer and, when the turn was cacheable, remembers it for next time.
    @Transactional
    public void completeTurn(PreparedTurn prepared, String answer) {
        ChatConversation conversation = conversationRepository.getReferenceById(prepared.conversationId());
        saveMessage(conversation, ChatRole.ASSISTANT, answer);

        CacheWrite write = prepared.cacheWrite();
        if (write != null) {
            semanticCache.store(write.courseId(), write.question(), write.questionVector(),
                    answer, prepared.citations());
        }
    }

    // The distinct documents a cached answer cites — the lecture attribution for a cache hit,
    // recovered from the citations rather than by re-running retrieval to find out.
    private static Set<UUID> documentIdsOf(List<Citation> citations) {
        return citations.stream()
                .map(Citation::documentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
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

    // `sources` is one passage per chunk, in the same order — the chunk's own text, or the section
    // it belongs to. The citation label still comes from the chunk, so [3] means the page retrieval
    // actually matched even when the model was shown the pages around it.
    private static String buildSystemPrompt(List<RetrievedChunk> chunks, List<String> sources) {
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
            // Where the passage sits in the document. Cheap for the model and worth having: it is
            // the difference between "expected search time is O(log n)" as a floating claim and as
            // a claim about skiplists.
            if (chunk.sectionPath() != null) {
                prompt.append(" · ").append(chunk.sectionPath());
            }
            prompt.append(")\n").append(sources.get(i).strip()).append("\n\n");
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
