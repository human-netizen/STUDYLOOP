package com.studyloop.backend.chat;

import com.studyloop.backend.analytics.QuestionLogService;
import com.studyloop.backend.chat.PreparedTurn.CacheWrite;
import com.studyloop.backend.chat.SemanticCacheService.CacheProbe;
import com.studyloop.backend.chat.SemanticCacheService.CachedAnswer;
import com.studyloop.backend.chat.dto.AskedBefore;
import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.chat.dto.ChatResponse;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.chat.dto.GeneralAnswerRequest;
import com.studyloop.backend.chat.dto.GeneralAnswerResponse;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.document.LanguageDetector;
import com.studyloop.backend.retrieval.RetrievalResult;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import com.studyloop.backend.retrieval.SectionExpander;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    // The same refusal in Bangla (Phase 19.3). It is a constant rather than a translation call for
    // the same reason the English one is: the gate refuses *without* reaching a provider, and the
    // whole value of that is that a refusal cannot fail, cannot cost anything and cannot be
    // hallucinated. A student who asked in Bangla and is told in English that their question is
    // not in the materials has been answered by a system that did not read their question.
    private static final String NOT_IN_MATERIALS_BN =
            "\u098f\u0987 \u0995\u09cb\u09b0\u09cd\u09b8\u09c7\u09b0 "
            + "\u0989\u09aa\u0995\u09b0\u09a3\u09c7 \u0986\u09ae\u09bf \u098f\u099f\u09bf "
            + "\u0996\u09c1\u0981\u099c\u09c7 \u09aa\u09be\u0987\u09a8\u09bf\u0964 "
            + "\u09aa\u09cd\u09b0\u09b6\u09cd\u09a8\u099f\u09bf \u0985\u09a8\u09cd\u09af"
            + "\u09ad\u09be\u09ac\u09c7 \u0995\u09b0\u09c1\u09a8, \u0985\u09a5\u09ac\u09be "
            + "\u098f\u0987 \u09ac\u09bf\u09b7\u09df\u09c7\u09b0 \u098f\u0995\u099f\u09bf "
            + "\u09a1\u0995\u09c1\u09ae\u09c7\u09a8\u09cd\u099f \u0986\u09aa\u09b2\u09cb\u09a1 "
            + "\u0995\u09b0\u09c1\u09a8\u0964";

    private final CourseAccess courseAccess;
    private final RetrievalService retrievalService;
    private final ChatClient chatClient;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ConfidenceGate confidenceGate;
    private final SemanticCacheService semanticCache;
    private final QuestionLogService questionLog;
    private final SectionExpander sectionExpander;
    private final LanguageDetector languageDetector;

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
                    prepared.citations(), prepared.questionEventId(), prepared.askedBefore());
        }

        String answer = chatClient.complete(prepared.messages());
        completeTurn(prepared, answer);
        return new ChatResponse(prepared.conversationId(), answer, prepared.citations(), null,
                prepared.askedBefore());
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
        // 19.3. Read off the *question*, not off the course's documents, and that is the whole
        // decision: a Bangla course can hold English papers and an English course can be asked
        // about in Bangla, so the only text that says what language this student wants an answer
        // in is the one they just typed. It costs a scan of one sentence and no provider call.
        Language language = languageDetector.detect(question);
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
            // 20.3, and a cache hit is the case it matters most on: this is the third time the same
            // student has asked the same thing, so it is also the answer most likely to be served
            // from storage. Telling them they asked this on the 4th is the difference between a
            // fast answer and a fast answer they have already read and not understood.
            AskedBefore repeated = askedBefore(courseId, actorId, probe.questionVector());
            saveMessage(conversation, ChatRole.ASSISTANT, cached.answer());
            // Still a question the class asked, so it still counts toward confusion analytics.
            // Who paid for the answer is a cost concern, not a teaching one. No retrieval ran, so
            // the lecture attribution comes from the citations stored with the cached answer, and
            // there is no top similarity to report.
            questionLog.recordGrounded(courseId, actorId, question, probe.questionVector(), null,
                    documentIdsOf(cached.citations()));
            return PreparedTurn.answered(conversation.getId(), cached.citations(), cached.answer(),
                    repeated);
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

        // 20.3, and computed before the gate rather than after it, so a question refused for the
        // third time still carries the header. That pairing is the one an instructor most needs to
        // see and the one a student is most owed an explanation for.
        AskedBefore repeated = askedBefore(courseId, actorId, questionVector);

        // Confidence gate: if nothing relevant came back, refuse deterministically instead of
        // letting the model answer from weak or absent context (and skip the provider call).
        if (confidenceGate.shouldRefuse(retrieval)) {
            String refusal = language == Language.BANGLA ? NOT_IN_MATERIALS_BN : NOT_IN_MATERIALS;
            saveMessage(conversation, ChatRole.ASSISTANT, refusal);
            // The most valuable row this table gets: a question the corpus could not answer. Its
            // id goes back to the client so the refusal can be escalated to the forum as *this*
            // question rather than as a copy of its text.
            UUID questionEventId =
                    questionLog.recordRefused(courseId, actorId, question, questionVector, topSimilarity);
            return PreparedTurn.refused(conversation.getId(), refusal, questionEventId, repeated);
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
        messages.add(LlmMessage.system(GroundedPrompt.system(
                chunks, sectionExpander.expand(chunks), language,
                repeated == null ? 0 : repeated.times())));
        messages.addAll(replayHistory(conversation.getId()));

        // Cache only what the cache could ever serve: an opening question, answered from the
        // materials, whose embedding we actually have. Refusals are excluded deliberately —
        // "I don't have that" is the one answer most likely to be wrong tomorrow.
        CacheWrite cacheWrite = opensThread && probe.questionVector() != null
                ? new CacheWrite(courseId, question, probe.questionVector())
                : null;
        return PreparedTurn.answerable(conversation.getId(), toCitations(chunks), messages,
                cacheWrite, repeated);
    }

    // Phase 20.2 — the escape hatch on a refusal: answer the question from the model's own
    // knowledge, say so, and record that it happened.
    //
    // It lives here rather than in a service of its own because it is a turn in a conversation and
    // needs what a turn needs: the membership check, the thread, the transcript. What it
    // deliberately does *not* share with prepare() is the grounded pipeline — no retrieval, no
    // gate, no citations, and **no semantic cache**, on either side. Not written, because an
    // ungrounded answer must never later be served to somebody who asked the course a question;
    // not read, because the student has already been told what the corpus had to say.
    //
    // The transcript is not replayed either. This answers the one question the course could not,
    // and the last line of that transcript is the refusal — feeding it back asks the model to
    // explain why it just declined.
    @Transactional
    public GeneralAnswerResponse general(UUID actorId, UUID courseId, GeneralAnswerRequest request) {
        courseAccess.requireMember(actorId, courseId);
        if (!chatClient.isConfigured()) {
            throw new ChatException("Chat provider is not configured.");
        }

        String question = request.question().trim();
        ChatConversation conversation = conversationRepository
                .findByIdAndCourseSpaceIdAndCreatedById(request.conversationId(), courseId, actorId)
                .orElseThrow(() -> new ChatConversationNotFoundException(request.conversationId()));

        // The question is already in this conversation — the refused turn saved it moments ago -
        // so saving it again would show the student asking twice. Only the answer is new.
        List<LlmMessage> messages = List.of(
                LlmMessage.system(GroundedPrompt.generalKnowledge(languageDetector.detect(question))),
                LlmMessage.user(question));

        String answer;
        try (var ignored = AiUsageContext.of(AiOperation.GENERAL_KNOWLEDGE)) {
            answer = chatClient.complete(messages);
        }

        saveMessage(conversation, ChatRole.GENERAL, answer);
        // Stamped on the refusal rather than written as a new question — see QuestionLogService.
        questionLog.recordEscalation(courseId, request.questionEventId());
        return new GeneralAnswerResponse(conversation.getId(), answer);
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
            // A GENERAL turn replays as the assistant's, carrying the one thing the model cannot
            // otherwise know: that this particular past answer did not come from the materials.
            // Without the marker a later grounded turn may reuse it and attach a [n] to it, which
            // would put a citation on the one paragraph in the transcript that never had a source.
            history.add(switch (message.getRole()) {
                case USER -> LlmMessage.user(message.getContent());
                case ASSISTANT -> LlmMessage.assistant(message.getContent());
                case GENERAL -> LlmMessage.assistant(
                        "(answered from general knowledge, not from the course materials)\n"
                        + message.getContent());
            });
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

    // The header, or null when this is not a repeat — which it is not on the overwhelming
    // majority of turns, at the cost of one filtered scan of the asker's own rows.
    private AskedBefore askedBefore(UUID courseId, UUID actorId, float[] questionVector) {
        Optional<QuestionLogService.Recurrence> recurrence =
                questionLog.recurrence(courseId, actorId, questionVector);
        return recurrence
                .map(found -> new AskedBefore(found.times(), found.lastAskedAt(), found.lastQuestion()))
                .orElse(null);
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
