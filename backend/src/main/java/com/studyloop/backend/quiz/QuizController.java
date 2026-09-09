package com.studyloop.backend.quiz;

import com.studyloop.backend.quiz.dto.AttemptResponse;
import com.studyloop.backend.quiz.dto.AttemptSummaryResponse;
import com.studyloop.backend.quiz.dto.GenerateQuizRequest;
import com.studyloop.backend.quiz.dto.PracticeSetResponse;
import com.studyloop.backend.quiz.dto.QuizResponse;
import com.studyloop.backend.quiz.dto.QuizSummaryResponse;
import com.studyloop.backend.quiz.dto.SubmitAttemptRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Generate and take quizzes built from a course's own materials. Any course member may generate
// a quiz (a study aid shared with the course), list them, and open one to take. The take view
// never includes the answer key — grading (Phase 7.2) reveals it.
@RestController
@RequestMapping("/api/v1/courses/{courseId}/quizzes")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;
    private final QuizGradingService gradingService;
    private final WrongAnswerQuizService wrongAnswerQuizService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuizResponse generate(Authentication authentication,
                                 @PathVariable UUID courseId,
                                 @Valid @RequestBody GenerateQuizRequest request) {
        return quizService.generate(UUID.fromString(authentication.getName()), courseId, request);
    }

    @GetMapping
    public List<QuizSummaryResponse> list(Authentication authentication, @PathVariable UUID courseId) {
        return quizService.list(UUID.fromString(authentication.getName()), courseId);
    }

    // Phase 28.5 — the caller's own missed questions, shaped as a quiz. Zero provider calls: it is
    // a read of `quiz_attempt_answers` and `review_states`, both written on every attempt since
    // Phase 7.2 and read until now only by the review queue.
    //
    // Declared before `/{quizId}` so the literal path is not read as a quiz id.
    @GetMapping("/wrong-answers")
    public PracticeSetResponse wrongAnswers(Authentication authentication,
                                            @PathVariable UUID courseId) {
        return wrongAnswerQuizService.practiceSet(UUID.fromString(authentication.getName()), courseId);
    }

    // Grading for that set. Not `/{quizId}/attempts`, because there is no quiz and no attempt row —
    // see WrongAnswerQuizService for why the original question rows are re-served rather than
    // copied. 200 rather than 201: nothing was created.
    @PostMapping("/wrong-answers/attempts")
    public AttemptResponse submitWrongAnswers(Authentication authentication,
                                              @PathVariable UUID courseId,
                                              @Valid @RequestBody SubmitAttemptRequest request) {
        return gradingService.submitPractice(UUID.fromString(authentication.getName()), courseId,
                request);
    }

    @GetMapping("/{quizId}")
    public QuizResponse getOne(Authentication authentication,
                               @PathVariable UUID courseId,
                               @PathVariable UUID quizId) {
        return quizService.getForTaking(UUID.fromString(authentication.getName()), courseId, quizId);
    }

    // Submit answers → graded result (score + per-question verdict, answer key, explanations).
    @PostMapping("/{quizId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public AttemptResponse submit(Authentication authentication,
                                  @PathVariable UUID courseId,
                                  @PathVariable UUID quizId,
                                  @Valid @RequestBody SubmitAttemptRequest request) {
        return gradingService.submit(UUID.fromString(authentication.getName()), courseId, quizId, request);
    }

    // The caller's own past attempts on this quiz, most recent first.
    @GetMapping("/{quizId}/attempts")
    public List<AttemptSummaryResponse> attempts(Authentication authentication,
                                                 @PathVariable UUID courseId,
                                                 @PathVariable UUID quizId) {
        return gradingService.listAttempts(UUID.fromString(authentication.getName()), courseId, quizId);
    }

    // Phase 27.4 - author or manager. 204, because there is nothing left to describe.
    @DeleteMapping("/{quizId}")
    public ResponseEntity<Void> delete(Authentication authentication,
                                       @PathVariable UUID courseId,
                                       @PathVariable UUID quizId) {
        quizService.delete(UUID.fromString(authentication.getName()), courseId, quizId);
        return ResponseEntity.noContent().build();
    }
}
