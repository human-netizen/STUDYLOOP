package com.studyloop.backend.chat;

import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.chat.dto.Citation;
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

// Drives a chat turn as a Server-Sent Events stream. The wire protocol is three named events:
//   meta  → { conversationId, citations }  (sent first, before any answer text)
//   delta → { text }                       (one per token as the model generates)
//   done  → { conversationId }             (stream finished cleanly)
// and, on failure, error → { message }. The heavy work runs on a dedicated executor so the
// request thread returns the emitter immediately; DB writes are delegated to ChatService's
// transactional methods (called externally here, so their proxies apply).
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    // Give a slow model response room to finish before the emitter times out (ms).
    private static final long STREAM_TIMEOUT_MS = 120_000L;
    private static final String ERROR_MESSAGE =
            "The assistant is temporarily unavailable. Please try again.";

    private final ChatService chatService;
    private final ChatClient chatClient;
    // Resolved by bean name (see AsyncConfig#chatStreamExecutor).
    private final Executor chatStreamExecutor;

    public SseEmitter stream(UUID actorId, UUID courseId, ChatRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        chatStreamExecutor.execute(() -> run(actorId, courseId, request, emitter));
        return emitter;
    }

    private void run(UUID actorId, UUID courseId, ChatRequest request, SseEmitter emitter) {
        try {
            PreparedTurn prepared = chatService.prepare(actorId, courseId, request);
            send(emitter, "meta", new MetaEvent(prepared.conversationId(), prepared.citations()));

            if (prepared.refused()) {
                // Gate tripped: no model call — emit the canned refusal as a single delta.
                send(emitter, "delta", new DeltaEvent(prepared.refusalText()));
                send(emitter, "done", new DoneEvent(prepared.conversationId()));
                emitter.complete();
                return;
            }

            String answer = chatClient.streamComplete(prepared.messages(),
                    token -> send(emitter, "delta", new DeltaEvent(token)));
            chatService.persistAssistant(prepared.conversationId(), answer);

            send(emitter, "done", new DoneEvent(prepared.conversationId()));
            emitter.complete();
        } catch (Exception e) {
            log.warn("Chat stream failed", e);
            trySend(emitter, "error", new ErrorEvent(ERROR_MESSAGE));
            emitter.completeWithError(e);
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

    public record MetaEvent(UUID conversationId, List<Citation> citations) { }

    public record DeltaEvent(String text) { }

    public record DoneEvent(UUID conversationId) { }

    public record ErrorEvent(String message) { }
}
