package com.studyloop.backend.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.chat.dto.ConversationSummary;
import com.studyloop.backend.chat.dto.ConversationTranscript;
import com.studyloop.backend.chat.dto.ConversationTranscript.TranscriptMessage;
import com.studyloop.backend.course.CourseAccess;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Phase 28.1 — the read path for chat.
//
// **Two tables have been written on every turn since Phase 5 and read by nothing.**
// `ChatController` had three POSTs and no GET; `conversationId` lived in React state and died on
// refresh, so every reload silently started a new thread while the model was still being handed the
// old one on any turn that did survive. The rows were not the problem — they were correct, indexed
// and growing. What was missing was anybody to show them to.
//
// It is a separate service from `ChatService` on purpose. That class is about *producing* a turn:
// retrieval, the confidence gate, the cache, the provider, the writes. Nothing here calls a
// provider or costs anything, and the two have no shared state beyond the repositories.
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    // Boot 4.1's modular web starter publishes no ObjectMapper bean (see BUGS.md), so — as in
    // SemanticCacheService, which reads the same shape out of the same kind of column — we keep our
    // own. Unknown fields are ignored: a citation written by an older shape of the record still
    // reads back rather than taking the whole transcript down with it.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CourseAccess courseAccess;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    // The caller's own threads in this course, newest first.
    @Transactional(readOnly = true)
    public List<ConversationSummary> list(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        return conversationRepository.findOwnedSummaries(courseId, actorId);
    }

    // One thread in full, with the sources each answer was shown with.
    //
    // **Two queries rather than 26.2's one, and deliberately.** `findOwnedHistory` folds the
    // ownership check into the message query, which is right on the hot path where the alternative
    // is a second WAN round trip per turn. Here it would be wrong: a thread that is not yours and a
    // thread of yours with no messages in it both come back as an empty list, and this endpoint has
    // to answer 404 for the first and 200 for the second. Loading the conversation first makes the
    // ownership failure a distinguishable one, and the transcript read is then unconditional.
    @Transactional(readOnly = true)
    public ConversationTranscript transcript(UUID actorId, UUID courseId, UUID conversationId) {
        courseAccess.requireMember(actorId, courseId);
        ChatConversation conversation = conversationRepository
                .findByIdAndCourseSpaceIdAndCreatedById(conversationId, courseId, actorId)
                .orElseThrow(() -> new ChatConversationNotFoundException(conversationId));

        List<ChatMessage> messages =
                messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<TranscriptMessage> turns = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            turns.add(new TranscriptMessage(message.getId(), message.getRole(),
                    message.getContent(), message.getCreatedAt(),
                    readCitations(message.getCitations())));
        }
        return new ConversationTranscript(conversation.getId(), conversation.getTitle(),
                conversation.getCreatedAt(), conversation.getUpdatedAt(), turns);
    }

    // Phase 27.4's last open bullet, which was parked here because a delete needs a list to be
    // reached from. The author and nobody else: a manager who can retire a document has no business
    // in a member's private thread, and there is no shared-ownership case to arbitrate — a
    // conversation has exactly one participant.
    //
    // The messages go with it through the foreign key's `on delete cascade` (V8) rather than
    // through a mapped collection, which is why no orphan sweep is needed and why deleting a
    // thousand-turn thread is one statement.
    @Transactional
    public void delete(UUID actorId, UUID courseId, UUID conversationId) {
        courseAccess.requireMember(actorId, courseId);
        ChatConversation conversation = conversationRepository
                .findByIdAndCourseSpaceIdAndCreatedById(conversationId, courseId, actorId)
                .orElseThrow(() -> new ChatConversationNotFoundException(conversationId));
        conversationRepository.delete(conversation);
    }

    private List<Citation> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Citation>>() { });
        } catch (Exception e) {
            // A transcript with one unreadable source list is still a transcript. The answer is
            // the thing the reader came back for; the markers degrade to plain text, which is
            // exactly how a pre-V29 turn renders.
            log.warn("Stored citations were unreadable: {}", e.getMessage());
            return List.of();
        }
    }
}
