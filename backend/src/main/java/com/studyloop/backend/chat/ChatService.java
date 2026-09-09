package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.analytics.QuestionLogService;
import com.studyloop.backend.chat.PreparedTurn.CacheWrite;
import com.studyloop.backend.chat.RetrievedTurn.Outcome;
import com.studyloop.backend.chat.SemanticCacheService.CacheProbe;
import com.studyloop.backend.chat.SemanticCacheService.CachedAnswer;
import com.studyloop.backend.chat.dto.AskedBefore;
import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.chat.dto.ChatResponse;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.chat.dto.GeneralAnswerRequest;
import com.studyloop.backend.chat.dto.GeneralAnswerResponse;
import com.studyloop.backend.config.ChatProperties;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.document.LanguageDetector;
import com.studyloop.backend.retrieval.DocumentScope;
import com.studyloop.backend.retrieval.RetrievalResult;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import com.studyloop.backend.retrieval.SectionExpander;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
//
// **Phase 26.2 split a turn into three phases, and the order of the three is the phase.**
//
//   readContext  — transactional. Everything the turn needs out of the database, then the one
//                  write that must happen before the model speaks: the student's question.
//   retrieve     — **no transaction**, because this is where the two provider calls live. The
//                  embedding and the cross-encoder are one to two seconds of network I/O, and
//                  running them inside a transaction pinned one of five pooled Supabase
//                  connections for the whole of it. The class comment here used to say the split
//                  from generation existed to prevent exactly that; the split had been made at the
//                  wrong seam, leaving the embed and the rerank on the transactional side.
//   recordTurn   — transactional again, and short: the settled answer and the question log.
//
// Reads before writes is not tidiness either. Hibernate flushes before a query, so a `save`
// interleaved between two selects costs its own round trip; the old order paid that three times.
// What the reordering deliberately does **not** do is defer the question log, which is written
// before generation on purpose — see recordTurn.
@Service
@RequiredArgsConstructor
public class ChatService {

    // How many chunks to ground on — matches the retrieval default and keeps the prompt small.
    private static final int RETRIEVAL_K = 6;
    // Cap replayed history so a long thread can't blow the prompt budget (last N turns only).
    // The question this turn is about is appended after the history rather than read back out of
    // it (26.2), so the window is N-1 prior messages plus the new one — the same N messages the
    // model has always been shown.
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

    // Phase 26.3 — the one tool this product offers, and there being one is the answer to the
    // objection that killed agentic chat the first time round.
    //
    // **No Wikipedia, no web search, no content generator, and therefore no fallback chain.** The
    // "Deliberately not building" entry objected that a chain ending at a generic answer makes no
    // answer attributable; a single tool has nothing to fall back *to*. A turn either searched
    // this course and cites it, or did not search and is stored as ChatRole.GENERAL — the role
    // Phase 20.2 already created, which already renders with "answered from general knowledge, not
    // from the course materials" and already replays carrying that marker so a later turn cannot
    // attach a citation to it.
    //
    // The description is the load-bearing text. It is what the model reads at the moment it
    // decides, which is why the instruction lives here rather than in the system prompt.
    private static final ToolSpec SEARCH_COURSE_MATERIALS = ToolSpec.oneStringArgument(
            "search_course_materials",
            "Search this course's own materials: its lecture notes, slides, textbook chapters and "
            + "the notes the class uploaded. This is the only source of truth for anything this "
            + "course teaches, so search whenever the question could plausibly be about the "
            + "materials — including when you already know the answer, because what matters is "
            + "what this course says. Answer without searching only for general knowledge the "
            + "course does not own. Returns numbered passages to cite as [n].",
            "query",
            "What to look for, in the student's own words where possible. A question or a phrase, "
            + "not keywords.");

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    // One turn's citations on the way into `chat_messages.citations` (Phase 28.1). Our own, for the
    // reason given in SemanticCacheService: Boot 4.1's modular web starter publishes no
    // ObjectMapper bean.
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    private final ChatProperties properties;

    // The non-streaming turn, kept as the simple fallback to /chat/stream. It shares the three
    // phases with the streaming path rather than restating them.
    //
    // @Transactional sits here as well as on those methods on purpose: Spring applies it through a
    // proxy, so the calls below — made on `this` — never reach it. Declaring it here means the
    // whole turn runs in one transaction instead of none. The cost is that the model call happens
    // inside it, which is exactly the thing the streaming path was split up to avoid; this
    // endpoint answers in one shot and has no seam to split at.
    @Transactional
    public ChatResponse chat(UUID actorId, UUID courseId, ChatRequest request) {
        PreparedTurn prepared = prepare(actorId, courseId, request);
        if (prepared.isAnswered()) {
            return new ChatResponse(prepared.conversationId(), prepared.finalAnswer(),
                    prepared.citations(), prepared.questionEventId(), prepared.answerEventId(),
                    prepared.askedBefore());
        }

        String answer = chatClient.complete(prepared.messages());
        completeTurn(prepared, answer);
        return new ChatResponse(prepared.conversationId(), answer, prepared.citations(), null,
                prepared.answerEventId(), prepared.askedBefore());
    }

    // The three phases composed, for callers that want a turn prepared in one call: the
    // non-streaming endpoint above, and the tests written before the split.
    //
    // The streaming path deliberately does *not* use this. It calls the three separately, because
    // only an external call goes through Spring's proxy — composed here they would run with
    // whatever transaction the caller happens to have, which for a stream is none at all.
    public PreparedTurn prepare(UUID actorId, UUID courseId, ChatRequest request) {
        TurnContext context = readContext(actorId, courseId, request);
        return recordTurn(context, retrieve(context));
    }

    // ------------------------------------------------------------------------------------------
    // Phase one: the reads.
    // ------------------------------------------------------------------------------------------

    // Resolves the thread, replays its transcript and records the question — in that order, which
    // is the order that costs one flush instead of three.
    @Transactional
    public TurnContext readContext(UUID actorId, UUID courseId, ChatRequest request) {
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

        ChatConversation conversation;
        List<LlmMessage> history;
        if (opensThread) {
            conversation = new ChatConversation();
            conversation.setCourseSpace(member.getCourseSpace());
            conversation.setCreatedBy(member.getUser());
            conversation.setTitle(titleFrom(question));
            // Flush so the generated id is available for the message saved below, and for the meta
            // event, which carries it before any answer text exists.
            conversation = conversationRepository.saveAndFlush(conversation);
            history = List.of();
        } else {
            // One statement for the ownership check and the transcript (26.2): the predicate that
            // decided whether this thread is yours now lives in the message query.
            List<ChatMessage> transcript = messageRepository
                    .findOwnedHistory(request.conversationId(), courseId, actorId);
            if (transcript.isEmpty()) {
                // Either the thread is not this member's or it does not exist, and a thread that
                // *is* theirs always has at least the question that opened it. Distinguishing the
                // two is worth a second query on the failing path and nothing on the working one:
                // the 404-vs-403 split is what stops a stranger learning that a conversation
                // exists.
                conversationRepository
                        .findByIdAndCourseSpaceIdAndCreatedById(request.conversationId(), courseId, actorId)
                        .orElseThrow(() -> new ChatConversationNotFoundException(request.conversationId()));
                conversation = conversationRepository.getReferenceById(request.conversationId());
                history = List.of();
            } else {
                // A proxy, and a proxy is all this is for: the new message needs the foreign key
                // and nothing reads the thread's own columns.
                conversation = transcript.get(0).getConversation();
                history = replay(transcript);
            }
        }

        // Recorded after the transcript is read rather than before it, which is what removes the
        // flush: with the write last, Hibernate has no reason to synchronise the session in the
        // middle of a query it is about to run.
        saveMessage(conversation, ChatRole.USER, question);
        return TurnContext.of(member, conversation.getId(), question, language, opensThread, history,
                DocumentScope.of(request.documentIds()));
    }

    // ------------------------------------------------------------------------------------------
    // Phase two: the provider calls, outside any transaction.
    // ------------------------------------------------------------------------------------------

    public RetrievedTurn retrieve(TurnContext context) {
        // 26.1. Said before the embedding call rather than after it, because the embedding call is
        // a second of the wait it is describing. The tool-calling path does *not* come through
        // here: it has already said "Searching your materials", which is the same news in the
        // words that fit that path, and saying both a millisecond apart reads like a stutter.
        TurnProgress.report(TurnProgress.LOOKING);
        return retrieve(context, context.question());
    }

    // The cache probe, retrieval, the confidence gate and the grounded prompt. Nothing here writes.
    //
    // `searchQuery` is the question itself on every path but one: when the model asked for the
    // search itself (26.3) it also chose what to search for, and its wording is what retrieval
    // runs. The *cache* is still probed with the student's question, because the cache is keyed on
    // what was asked and not on how it was looked up.
    public RetrievedTurn retrieve(TurnContext context, String searchQuery) {
        boolean askedAsTyped = searchQuery.equals(context.question());

        // **A scoped turn neither reads the cache nor writes to it (Phase 28.2), and that is not
        // caution — it is what the cache's key can and cannot say.** `chat_cache_entries` is keyed
        // on the course and the question's embedding, with no room for *where the reader was
        // looking*. Probing it from a scoped turn would answer "what is a rotation?" aimed at
        // chapter 6 with an answer built from chapter 12, citing pages the reader deliberately
        // excluded; storing one would do the same damage in the other direction, to the next
        // person who asks the question of the whole course. Widening the key is the alternative and
        // it is the wrong trade: it would fragment the cache by subset — one entry per question per
        // combination of documents — which is a cache that never hits.
        boolean cacheable = context.opensThread() && context.scope().isWholeCourse();
        CacheProbe probe = cacheable
                ? semanticCache.probe(context.courseId(), context.question())
                : CacheProbe.unavailable();
        if (probe.isHit()) {
            CachedAnswer cached = probe.hit();
            // 20.3, and a cache hit is the case it matters most on: this is the third time the same
            // student has asked the same thing, so it is also the answer most likely to be served
            // from storage. Telling them they asked this on the 4th is the difference between a
            // fast answer and a fast answer they have already read and not understood.
            AskedBefore repeated = askedBefore(context, probe.questionVector());
            // No retrieval ran, so the lecture attribution comes from the citations stored with the
            // cached answer, and there is no top similarity to report.
            return new RetrievedTurn(Outcome.CACHED, cached.citations(), List.of(), null,
                    cached.answer(), null, repeated, probe.questionVector(), null,
                    documentIdsOf(cached.citations()));
        }

        // Ground on the course's materials (the same hybrid retrieval the search API uses),
        // reusing the embedding the probe just computed so a cache miss costs one embedding call
        // rather than two. The membership resolved during the read phase is handed down rather
        // than resolved a second time (26.2) — it was the one round trip on this path that was
        // pure duplication.
        //
        // The reuse is dropped when the model rewrote the query: the probe's vector is an
        // embedding of the student's sentence, and searching with it while claiming to search for
        // something else would make the tool's argument decorative.
        RetrievalResult retrieval = retrievalService.searchAsMember(
                context.member(), searchQuery, askedAsTyped ? probe.questionVector() : null,
                RETRIEVAL_K, context.scope());
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
        AskedBefore repeated = askedBefore(context, questionVector);

        // Confidence gate: if nothing relevant came back, refuse deterministically instead of
        // letting the model answer from weak or absent context (and skip the provider call).
        if (confidenceGate.shouldRefuse(retrieval)) {
            String refusal = context.language() == Language.BANGLA ? NOT_IN_MATERIALS_BN : NOT_IN_MATERIALS;
            return new RetrievedTurn(Outcome.REFUSED, List.of(), List.of(), null, refusal, null,
                    repeated, questionVector, topSimilarity, Set.of());
        }

        // The sources are the retrieved chunks expanded to their sections (13.5): retrieval picked
        // them on precision, and the model reads them with the surrounding paragraph it needs.
        String sources = GroundedPrompt.sourcesBlock(chunks, sectionExpander.expand(chunks));

        // system prompt (numbered sources) → prior turns → the question this turn is about, which
        // is appended here because the read phase deliberately looked at the transcript before
        // writing it.
        List<LlmMessage> messages = new ArrayList<>(context.history().size() + 2);
        messages.add(LlmMessage.system(
                GroundedPrompt.instructionsFor(context.language(), repeated == null ? 0 : repeated.times())
                + sources));
        messages.addAll(context.history());
        messages.add(LlmMessage.user(context.question()));

        // Cache only what the cache could ever serve: an opening question, over the whole course
        // (28.2), answered from the materials, whose embedding we actually have. Refusals are
        // excluded deliberately — "I don't have that" is the one answer most likely to be wrong
        // tomorrow. `cacheable` is the same flag the probe was gated on, so a turn that could not
        // read the cache cannot write to it either; on a scoped turn `probe.questionVector()` is
        // null anyway, and the flag is here to make the rule visible rather than incidental.
        CacheWrite cacheWrite = cacheable && probe.questionVector() != null
                ? new CacheWrite(context.courseId(), context.question(), probe.questionVector())
                : null;
        TurnProgress.report(TurnProgress.WRITING);
        return new RetrievedTurn(Outcome.GROUNDED, toCitations(chunks), messages, sources, null,
                cacheWrite, repeated, questionVector, topSimilarity,
                chunks.stream().map(RetrievedChunk::documentId).collect(Collectors.toSet()));
    }

    // ------------------------------------------------------------------------------------------
    // Phase three: the writes.
    // ------------------------------------------------------------------------------------------

    // Persists what retrieval settled and hands back the turn the caller streams against.
    //
    // **The question log is written here, before generation, and that is deliberate** — the same
    // decision the pre-26.2 code documented at this point. The question was asked and the
    // materials could or could not answer it, and both of those are settled facts before the model
    // speaks. Waiting for the answer would lose every turn where the provider then times out. The
    // reordering this sub-phase did moves reads ahead of writes; it does not move this write after
    // the model call.
    @Transactional
    public PreparedTurn recordTurn(TurnContext context, RetrievedTurn retrieved) {
        return switch (retrieved.outcome()) {
            case CACHED -> {
                // The cached answer's own citations, not a fresh retrieval's: what is being stored
                // is what this reader was shown (Phase 28.1).
                saveMessage(reference(context), ChatRole.ASSISTANT, retrieved.settledAnswer(),
                        retrieved.citations());
                // Still a question the class asked, so it still counts toward confusion analytics.
                // Who paid for the answer is a cost concern, not a teaching one.
                UUID cachedEventId = questionLog.recordGrounded(context.courseId(),
                        context.actorId(), context.question(), retrieved.questionVector(), null,
                        retrieved.documentIds());
                yield PreparedTurn.answered(context.conversationId(), retrieved.citations(),
                        retrieved.settledAnswer(), retrieved.askedBefore(), cachedEventId);
            }
            case REFUSED -> {
                saveMessage(reference(context), ChatRole.ASSISTANT, retrieved.settledAnswer());
                // The most valuable row this table gets: a question the corpus could not answer.
                // Its id goes back to the client so the refusal can be escalated to the forum as
                // *this* question rather than as a copy of its text.
                UUID questionEventId = questionLog.recordRefused(context.courseId(),
                        context.actorId(), context.question(), retrieved.questionVector(),
                        retrieved.topSimilarity());
                yield PreparedTurn.refused(context.conversationId(), retrieved.settledAnswer(),
                        questionEventId, retrieved.askedBefore());
            }
            case GROUNDED -> {
                UUID answerEventId = questionLog.recordGrounded(context.courseId(),
                        context.actorId(), context.question(), retrieved.questionVector(),
                        retrieved.topSimilarity(), retrieved.documentIds());
                yield PreparedTurn.answerable(context.conversationId(), retrieved.citations(),
                        retrieved.messages(), retrieved.cacheWrite(), retrieved.askedBefore(),
                        answerEventId);
            }
        };
    }

    // Saves the finished answer and, when the turn was cacheable, remembers it for next time.
    @Transactional
    public void completeTurn(PreparedTurn prepared, String answer) {
        ChatConversation conversation = conversationRepository.getReferenceById(prepared.conversationId());
        saveMessage(conversation, ChatRole.ASSISTANT, answer, prepared.citations());

        CacheWrite write = prepared.cacheWrite();
        if (write != null) {
            semanticCache.store(write.courseId(), write.question(), write.questionVector(),
                    answer, prepared.citations());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Phase 26.3 — the turn where the model decides whether to retrieve at all.
    // ------------------------------------------------------------------------------------------

    // Whether a turn should be routed through the tool rather than through mandatory retrieval.
    // Off by default: it removes a provider call from a question the course does not own and adds
    // one to a question it does, and the second kind is the common one. 11.3's rule — the flag and
    // the run that justifies it are separate events — applies to this switch more than to any
    // other in the project, because this is the one that could make the product slower.
    public boolean toolCallingEnabled() {
        return properties.toolCalling();
    }

    public ToolSpec searchTool() {
        return SEARCH_COURSE_MATERIALS;
    }

    // The conversation as the router sees it: the rules for both outcomes, the transcript, and the
    // question. No sources, because whether there are any is what this call decides.
    public List<LlmMessage> routerMessages(TurnContext context) {
        List<LlmMessage> messages = new ArrayList<>(context.history().size() + 2);
        messages.add(LlmMessage.system(GroundedPrompt.router(context.language())));
        messages.addAll(context.history());
        messages.add(LlmMessage.user(context.question()));
        return messages;
    }

    // Runs the search the model asked for, and reports where the turn has got to while it does.
    //
    // The argument is read leniently: a model that sends the wrong key, or an empty one, gets the
    // student's own question searched rather than an exception that loses the turn. A tool that
    // throws on a malformed argument turns a model's slip into a 500.
    public RetrievedTurn runSearchTool(TurnContext context, String query) {
        TurnProgress.report(TurnProgress.SEARCHING);
        String search = query == null || query.isBlank() ? context.question() : query.trim();
        return retrieve(context, search);
    }

    // A turn the model answered without searching (26.3). Stored as GENERAL — Phase 20.2's role,
    // with 20.2's label and 20.2's replay behaviour, so an answer that did not come from the
    // materials says so on screen and keeps saying so in every later turn of the thread.
    //
    // **No question_events row, and that is a decision rather than an omission.** A row in that
    // table means "the corpus was asked this and could, or could not, answer it" — it is what the
    // instructor's confusion view counts and what "ask the class" attaches a thread to. A turn
    // that never searched asked the corpus nothing, so there is no such fact to record, and
    // writing one either way would make the refusal rate a number about the model's routing rather
    // than about the materials. How often this happens is visible instead as the ratio of
    // CHAT_ROUTE to CHAT_STREAM in the cost dashboard.
    @Transactional
    public void recordUnsearchedTurn(TurnContext context, String answer) {
        saveMessage(reference(context), ChatRole.GENERAL, answer);
    }

    // Phase 20.2 — the escape hatch on a refusal: answer the question from the model's own
    // knowledge, say so, and record that it happened.
    //
    // It lives here rather than in a service of its own because it is a turn in a conversation and
    // needs what a turn needs: the membership check, the thread, the transcript. What it
    // deliberately does *not* share with the grounded path is the pipeline — no retrieval, no
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

    // The distinct documents a cached answer cites — the lecture attribution for a cache hit,
    // recovered from the citations rather than by re-running retrieval to find out.
    private static Set<UUID> documentIdsOf(List<Citation> citations) {
        return citations.stream()
                .map(Citation::documentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    // A proxy, not a load: the row is known to exist because the read phase either created it or
    // fetched it, and all this reference is for is the foreign key on the message being saved.
    private ChatConversation reference(TurnContext context) {
        return conversationRepository.getReferenceById(context.conversationId());
    }

    // Prior turns, oldest-first, capped to leave room for the question appended after them.
    private List<LlmMessage> replay(List<ChatMessage> all) {
        int window = MAX_HISTORY_MESSAGES - 1;
        List<ChatMessage> recent = all.size() > window
                ? all.subList(all.size() - window, all.size())
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

    // A turn with no sources: every USER turn, every refusal, and every GENERAL answer. The empty
    // list is written rather than left null because the column is `not null` and because "this turn
    // had no sources" is a fact worth being able to read back, not an absence of data.
    private void saveMessage(ChatConversation conversation, ChatRole role, String content) {
        saveMessage(conversation, role, content, List.of());
    }

    // Phase 28.1 — the same write, keeping the sources the answer was shown with.
    //
    // Serialised here rather than mapped as a relationship: a citation names a chunk that may later
    // be deleted (27.3), and a foreign key would make the transcript's survival depend on the
    // corpus not changing. What is stored is the snapshot the reader saw.
    private void saveMessage(ChatConversation conversation, ChatRole role, String content,
                             List<Citation> citations) {
        ChatMessage message = new ChatMessage();
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content);
        message.setCitations(citationsJson(citations));
        messageRepository.save(message);
    }

    // Never throws: a turn that cannot serialise its sources is still a turn, and losing the
    // markers is survivable in a way that losing the answer is not — the same trade
    // `SemanticCacheService` makes on the way back out.
    private String citationsJson(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (Exception e) {
            log.warn("Citations could not be stored with the turn: {}", e.getMessage());
            return "[]";
        }
    }

    // The header, or null when this is not a repeat — which it is not on the overwhelming
    // majority of turns, at the cost of one filtered scan of the asker's own rows.
    private AskedBefore askedBefore(TurnContext context, float[] questionVector) {
        Optional<QuestionLogService.Recurrence> recurrence =
                questionLog.recurrence(context.courseId(), context.actorId(), questionVector);
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
