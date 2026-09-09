package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.dto.AskedBefore;
import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// Drives a chat turn as a Server-Sent Events stream. The wire protocol is four named events:
//   stage → { stage }                      (zero or more, before the answer starts)
//   meta  → { conversationId, citations }  (once, before any answer text)
//   delta → { text }                       (one per token as the model generates)
//   done  → { conversationId }             (stream finished cleanly)
// and, on failure, error → { message }. The heavy work runs on a dedicated executor so the
// request thread returns the emitter immediately; DB writes are delegated to ChatService's
// transactional methods (called externally here, so their proxies apply).
//
// **`stage` is Phase 26.1, and it exists because this endpoint used to send nothing for four
// seconds.** `meta` could only be sent once the whole read-retrieve-gate pipeline had finished, so
// the browser held an empty bubble with a cursor in it for the entire wait and then produced an
// answer. The stage events say which step is running, starting from the instant the request lands,
// and they stop at the first `delta` for the obvious reason: once text is arriving, the text is
// the progress report.
//
// **Phase 26.2 is why the three phases are called separately here.** Spring's `@Transactional`
// works through a proxy, so a service method calling its own siblings gets no transaction at all.
// Calling `readContext`, `retrieve` and `recordTurn` from outside is what puts a transaction
// around the two that need one and, more importantly, leaves `retrieve` — which makes both
// provider calls — with no pooled Supabase connection in its hand.
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    // Give a slow model response room to finish before the emitter times out (ms).
    private static final long STREAM_TIMEOUT_MS = 120_000L;
    private static final String ERROR_MESSAGE =
            "The assistant is temporarily unavailable. Please try again.";

    // Boot 4.1's modular web starter publishes no ObjectMapper bean (see BUGS.md), so — as
    // elsewhere in the project — we keep our own. It reads one thing: the `query` a tool call
    // asked for.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ChatService chatService;
    private final ChatClient chatClient;
    // Resolved by bean name (see AsyncConfig#chatStreamExecutor).
    private final Executor chatStreamExecutor;

    public SseEmitter stream(UUID actorId, UUID courseId, ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        chatStreamExecutor.execute(() -> run(actorId, courseId, request, emitter));
        return emitter;
    }

    // Runs on the chat executor, not the request thread, so AiUsageAttributionFilter's scope isn't
    // in force here — the actor is re-established for the length of the turn. Without it the whole
    // streaming path, which is how the UI actually asks questions, would bill to nobody and count
    // against nobody's budget.
    //
    // The progress scope wraps everything, including the read phase, because the first stage
    // sentence is worth more than any other: it is the one that turns a blank bubble into a
    // machine that is visibly working.
    private void run(UUID actorId, UUID courseId, ChatRequest request, SseEmitter emitter) {
        try (var ignored = AiUsageContext.actor(actorId);
             var stages = TurnProgress.to(stage -> send(emitter, "stage", new StageEvent(stage)))) {

            if (chatService.toolCallingEnabled()) {
                runWithTools(actorId, courseId, request, emitter);
                return;
            }

            TurnProgress.report(TurnProgress.LOOKING);
            TurnContext context = chatService.readContext(actorId, courseId, request);
            PreparedTurn prepared = chatService.recordTurn(context, chatService.retrieve(context));
            send(emitter, "meta", new MetaEvent(prepared.conversationId(), prepared.citations(),
                    prepared.questionEventId(), prepared.answerEventId(), prepared.askedBefore()));

            if (prepared.isAnswered()) {
                // The confidence gate refused, or the semantic cache already had this answer.
                // Either way there is nothing to generate — emit the text as a single delta. The
                // client sees the same event sequence as a generated answer, just faster.
                send(emitter, "delta", new DeltaEvent(prepared.finalAnswer()));
                send(emitter, "done", new DoneEvent(prepared.conversationId()));
                emitter.complete();
                return;
            }

            String answer = chatClient.streamComplete(prepared.messages(),
                    token -> send(emitter, "delta", new DeltaEvent(token)));
            chatService.completeTurn(prepared, answer);

            send(emitter, "done", new DoneEvent(prepared.conversationId()));
            emitter.complete();
        } catch (Exception e) {
            log.warn("Chat stream failed", e);
            trySend(emitter, "error", new ErrorEvent(ERROR_MESSAGE));
            emitter.completeWithError(e);
        }
    }

    // Phase 26.3 — the same turn, with retrieval offered to the model as a tool instead of run for
    // it. Behind `studyloop.chat.tool-calling`, off by default.
    //
    // **`meta` moves, and it has to.** On this path the citations do not exist until the model has
    // decided to search and the search has run, so the event is sent the moment they are settled —
    // from inside the tool — or, if the model never searched, at the first token, carrying no
    // citations. The wire order the client depends on (`stage`* → `meta` → `delta`* → `done`) is
    // unchanged either way, because both of those moments are before the first delta.
    private void runWithTools(UUID actorId, UUID courseId, ChatRequest request, SseEmitter emitter) {
        TurnProgress.report(TurnProgress.THINKING);
        TurnContext context = chatService.readContext(actorId, courseId, request);

        // What the tool did, read after the stream ends. An AtomicReference rather than a field
        // because a turn is one object's worth of state on one thread, and the lambda below needs
        // to hand it back out.
        AtomicReference<PreparedTurn> searched = new AtomicReference<>();
        AtomicBoolean metaSent = new AtomicBoolean();

        String answer = chatClient.streamWithTools(
                chatService.routerMessages(context),
                List.of(chatService.searchTool()),
                call -> {
                    RetrievedTurn retrieved = chatService.runSearchTool(context, queryOf(call));
                    PreparedTurn prepared = chatService.recordTurn(context, retrieved);
                    searched.set(prepared);
                    sendMetaOnce(emitter, metaSent, prepared.conversationId(), prepared.citations(),
                            prepared.questionEventId(), prepared.answerEventId(), prepared.askedBefore());
                    // A cache hit or a refusal ends the turn here: the text exists already, so the
                    // model is not asked to write over the top of it.
                    return prepared.isAnswered()
                            ? ToolResult.settled(prepared.finalAnswer())
                            : ToolResult.of(retrieved.sources());
                },
                token -> {
                    sendMetaOnce(emitter, metaSent, context.conversationId(), List.of(), null, null, null);
                    send(emitter, "delta", new DeltaEvent(token));
                });

        PreparedTurn prepared = searched.get();
        if (prepared == null) {
            // The model answered without searching. Stored as GENERAL, which is Phase 20.2's role
            // and 20.2's label — so an answer that did not come from the materials says so, here
            // and in every later turn of this thread.
            chatService.recordUnsearchedTurn(context, answer);
        } else if (!prepared.isAnswered()) {
            chatService.completeTurn(prepared, answer);
        }

        sendMetaOnce(emitter, metaSent, context.conversationId(), List.of(), null, null, null);
        send(emitter, "done", new DoneEvent(context.conversationId()));
        emitter.complete();
    }

    // The `query` the model asked to search for. A malformed argument object yields null, which
    // the tool reads as "search what the student typed" — a model's slip should cost the rewrite,
    // not the turn.
    private String queryOf(ToolCall call) {
        try {
            JsonNode arguments = objectMapper.readTree(call.arguments());
            return arguments.path("query").asText(null);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read the tool arguments {}: {}", call.arguments(), e.getMessage());
            return null;
        }
    }

    // meta is sent exactly once per turn, by whichever of the two moments comes first.
    private void sendMetaOnce(SseEmitter emitter, AtomicBoolean sent, UUID conversationId,
                              List<Citation> citations, UUID questionEventId, UUID answerEventId,
                              AskedBefore askedBefore) {
        if (sent.compareAndSet(false, true)) {
            send(emitter, "meta", new MetaEvent(conversationId, citations, questionEventId,
                    answerEventId, askedBefore));
        }
    }

    // Sends an event, converting the IOException into an unchecked one so it surfaces as a
    // stream failure (caught by run()); the token callback needs an unchecked signature.
    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Best-effort error notification; the client may already be gone, so swallow failures.
    private void trySend(SseEmitter emitter, String name, Object data) {
        try {
            send(emitter, name, data);
        } catch (RuntimeException ignored) {
            // Client disconnected before we could report the error — nothing more to do.
        }
    }

    // The step the turn is on, in a sentence a student can read. No percentage, deliberately:
    // 25.2's ingestion bar has a denominator (pages) and a chat turn does not, and a bar that
    // advances at an unpredictable rate from an unknown total invites a reader to work out an
    // arrival time from a number that was invented.
    public record StageEvent(String stage) { }

    // questionEventId is non-null only when the gate refused — it is the client's handle for
    // escalating that refusal, to the forum (9.2) or to general knowledge (20.2). answerEventId is
    // non-null on every logged turn and is what a verdict on the answer attaches to (28.3).
    // askedBefore is non-null only on a repeat (20.3). All three ride the meta event, which is sent
    // before the first token, so the header is on screen while the answer is still arriving.
    //
    // On the streaming-with-tool path the meta event can be sent by the *token* callback, before
    // the search has settled, and it then carries nulls for all three — which is correct rather
    // than lossy: that branch is the one where the model answered without retrieving, so there is
    // no citation list, no refusal and no logged question for a verdict to be about.
    public record MetaEvent(UUID conversationId, List<Citation> citations, UUID questionEventId,
                            UUID answerEventId, AskedBefore askedBefore) { }

    public record DeltaEvent(String text) { }

    public record DoneEvent(UUID conversationId) { }

    public record ErrorEvent(String message) { }
}
