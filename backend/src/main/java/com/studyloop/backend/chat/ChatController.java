package com.studyloop.backend.chat;

import com.studyloop.backend.analytics.AnswerFeedbackService;
import com.studyloop.backend.chat.dto.AnswerFeedbackRequest;
import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.chat.dto.ChatResponse;
import com.studyloop.backend.chat.dto.ConversationSummary;
import com.studyloop.backend.chat.dto.ConversationTranscript;
import com.studyloop.backend.chat.dto.GeneralAnswerRequest;
import com.studyloop.backend.chat.dto.GeneralAnswerResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

// Ask a grounded question about a course's materials. Any course member may chat; the answer
// cites the course's own chunks as [n]. Pass conversationId to continue a thread, or omit it
// to start a new one (the response returns the id to reuse).
//
// **Three POSTs and no GET until Phase 28.1**, which is the whole of that sub-phase's argument:
// the thread id was returned to the client, held in React state, and lost on refresh, so the
// history the model was being replayed had no reader anywhere in the product.
@RestController
@RequestMapping("/api/v1/courses/{courseId}/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;
    private final ChatHistoryService chatHistoryService;
    private final AnswerFeedbackService answerFeedbackService;

    // Non-streaming answer: the full ChatResponse in one JSON body. Kept as the simple fallback.
    @PostMapping
    public ChatResponse chat(Authentication authentication,
                             @PathVariable UUID courseId,
                             @Valid @RequestBody ChatRequest request) {
        return chatService.chat(UUID.fromString(authentication.getName()), courseId, request);
    }

    // Streaming answer: the same turn delivered as Server-Sent Events (meta → delta* → done),
    // so the UI can render tokens as they arrive. See ChatStreamService for the event shapes.
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(Authentication authentication,
                                 @PathVariable UUID courseId,
                                 @Valid @RequestBody ChatRequest request) {
        return chatStreamService.stream(UUID.fromString(authentication.getName()), courseId, request);
    }

    // Phase 28.1 — the caller's own threads in this course, newest first. Each row carries the
    // first question as its title, so the list can be rendered without opening anything.
    @GetMapping("/conversations")
    public List<ConversationSummary> conversations(Authentication authentication,
                                                   @PathVariable UUID courseId) {
        return chatHistoryService.list(UUID.fromString(authentication.getName()), courseId);
    }

    // One thread in full, with the sources each answer was shown with. 404 when it is not the
    // caller's — the same answer a stranger gets for a thread that does not exist, which is what
    // stops the endpoint confirming that somebody else's conversation is there.
    @GetMapping("/conversations/{conversationId}")
    public ConversationTranscript transcript(Authentication authentication,
                                             @PathVariable UUID courseId,
                                             @PathVariable UUID conversationId) {
        return chatHistoryService.transcript(UUID.fromString(authentication.getName()), courseId,
                conversationId);
    }

    // Phase 27.4's delete, which waited for this list to be reached from. The author only, and 204
    // because there is nothing left to describe.
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(Authentication authentication,
                                                   @PathVariable UUID courseId,
                                                   @PathVariable UUID conversationId) {
        chatHistoryService.delete(UUID.fromString(authentication.getName()), courseId, conversationId);
        return ResponseEntity.noContent().build();
    }

    // Phase 28.3 — a verdict on one answer, with the passages it was shown with. 204: the client
    // has nothing to render from the response, and a body would only invite it to.
    //
    // On the chat controller rather than the analytics one because this is a reader's action on
    // their own answer, and `/analytics` is manager-only — the person who can report an answer is
    // by definition not the person who reads the report.
    @PostMapping("/feedback")
    public ResponseEntity<Void> feedback(Authentication authentication,
                                         @PathVariable UUID courseId,
                                         @Valid @RequestBody AnswerFeedbackRequest request) {
        answerFeedbackService.submit(UUID.fromString(authentication.getName()), courseId, request);
        return ResponseEntity.noContent().build();
    }

    // Phase 20.2 — the same question, answered from general knowledge and labelled as not
    // coming from this course. Reachable only from a refusal, and not streamed: it is one short
    // answer behind an explicit second click, and the reason the streaming path exists is to make a
    // *long* grounded answer feel immediate.
    @PostMapping("/general")
    public GeneralAnswerResponse general(Authentication authentication,
                                         @PathVariable UUID courseId,
                                         @Valid @RequestBody GeneralAnswerRequest request) {
        return chatService.general(UUID.fromString(authentication.getName()), courseId, request);
    }
}
