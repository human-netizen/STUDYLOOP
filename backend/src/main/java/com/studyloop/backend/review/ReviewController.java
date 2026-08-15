package com.studyloop.backend.review;

import com.studyloop.backend.review.dto.GradeReviewRequest;
import com.studyloop.backend.review.dto.ReviewCardResponse;
import com.studyloop.backend.review.dto.ReviewResultResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// The spaced-repetition queue. Deliberately NOT nested under /courses/{id}: the point of the
// queue is that it spans every course you study, so it hangs off the user instead. Pass
// ?courseId= to narrow it to one course.
@RestController
@RequestMapping("/api/v1/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/queue")
    public List<ReviewCardResponse> queue(Authentication authentication,
                                          @RequestParam(required = false) UUID courseId) {
        return reviewService.queue(UUID.fromString(authentication.getName()), courseId);
    }

    // A cheap badge count for the nav, so the UI doesn't fetch the whole deck to show a number.
    @GetMapping("/due-count")
    public DueCount dueCount(Authentication authentication,
                             @RequestParam(required = false) UUID courseId) {
        return new DueCount(reviewService.dueCount(UUID.fromString(authentication.getName()), courseId));
    }

    @PostMapping("/{cardId}")
    public ReviewResultResponse grade(Authentication authentication,
                                      @PathVariable UUID cardId,
                                      @Valid @RequestBody GradeReviewRequest request) {
        return reviewService.grade(UUID.fromString(authentication.getName()), cardId, request);
    }

    public record DueCount(long due) {
    }
}
