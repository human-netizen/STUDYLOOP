package com.studyloop.backend.chat;

import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.chat.dto.ChatResponse;
import com.studyloop.backend.chat.dto.GeneralAnswerRequest;
import com.studyloop.backend.chat.dto.GeneralAnswerResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

// Ask a grounded question about a course's materials. Any course member may chat; the answer
// cites the course's own chunks as [n]. Pass conversationId to continue a thread, or omit it
// to start a new one (the response returns the id to reuse).
@RestController
@RequestMapping("/api/v1/courses/{courseId}/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;

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
