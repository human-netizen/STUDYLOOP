package com.studyloop.backend.forum;

import com.studyloop.backend.forum.dto.CreateThreadRequest;
import com.studyloop.backend.forum.dto.ForumThreadDetail;
import com.studyloop.backend.forum.dto.ForumThreadSummary;
import com.studyloop.backend.forum.dto.PostAnswerRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// The course forum: where a question the assistant refused goes to be answered by people.
// Members read, ask and reply; only managers accept an answer, because accepting writes it into
// the corpus (see ForumService).
@RestController
@RequestMapping("/api/v1/courses/{courseId}/forum/threads")
@RequiredArgsConstructor
public class ForumController {

    private final ForumService forumService;

    // `status` narrows the list to OPEN or ANSWERED; omitted means everything.
    @GetMapping
    public List<ForumThreadSummary> list(Authentication authentication,
                                         @PathVariable UUID courseId,
                                         @RequestParam(name = "status", required = false)
                                         ForumThreadStatus status) {
        return forumService.list(UUID.fromString(authentication.getName()), courseId, status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ForumThreadDetail open(Authentication authentication,
                                  @PathVariable UUID courseId,
                                  @Valid @RequestBody CreateThreadRequest request) {
        return forumService.open(UUID.fromString(authentication.getName()), courseId, request);
    }

    @GetMapping("/{threadId}")
    public ForumThreadDetail get(Authentication authentication,
                                 @PathVariable UUID courseId,
                                 @PathVariable UUID threadId) {
        return forumService.get(UUID.fromString(authentication.getName()), courseId, threadId);
    }

    // Returns the whole thread rather than the created answer alone: the client's next screen is
    // the thread, and a reply out of context tells it nothing about what accepting one did.
    @PostMapping("/{threadId}/answers")
    @ResponseStatus(HttpStatus.CREATED)
    public ForumThreadDetail answer(Authentication authentication,
                                    @PathVariable UUID courseId,
                                    @PathVariable UUID threadId,
                                    @Valid @RequestBody PostAnswerRequest request) {
        return forumService.answer(UUID.fromString(authentication.getName()), courseId, threadId, request);
    }

    @PostMapping("/{threadId}/answers/{answerId}/accept")
    public ForumThreadDetail accept(Authentication authentication,
                                    @PathVariable UUID courseId,
                                    @PathVariable UUID threadId,
                                    @PathVariable UUID answerId) {
        return forumService.accept(UUID.fromString(authentication.getName()), courseId, threadId, answerId);
    }
}
