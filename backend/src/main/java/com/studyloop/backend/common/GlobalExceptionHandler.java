package com.studyloop.backend.common;

import com.studyloop.backend.auth.EmailAlreadyRegisteredException;
import com.studyloop.backend.auth.InvalidCredentialsException;
import com.studyloop.backend.auth.InvalidTokenException;
import com.studyloop.backend.auth.UserNotFoundException;
import com.studyloop.backend.course.CourseNotFoundException;
import com.studyloop.backend.course.InsufficientCourseRoleException;
import com.studyloop.backend.course.InviteEmailMismatchException;
import com.studyloop.backend.course.InviteExpiredException;
import com.studyloop.backend.course.InviteNotFoundException;
import com.studyloop.backend.course.NotACourseMemberException;
import com.studyloop.backend.chat.ChatConversationNotFoundException;
import com.studyloop.backend.chat.ChatException;
import com.studyloop.backend.document.DocumentNotFoundException;
import com.studyloop.backend.document.DocumentStorageException;
import com.studyloop.backend.document.DuplicateDocumentException;
import com.studyloop.backend.document.EmptyDocumentException;
import com.studyloop.backend.document.NoSummaryMaterialException;
import com.studyloop.backend.document.NoteNotReadyException;
import com.studyloop.backend.document.SummaryGenerationException;
import com.studyloop.backend.document.UnsupportedDocumentTypeException;
import com.studyloop.backend.flashcard.FlashcardGenerationException;
import com.studyloop.backend.forum.AssistantAnswerNotAcceptableException;
import com.studyloop.backend.forum.ForumAnswerNotFoundException;
import com.studyloop.backend.forum.ForumThreadNotFoundException;
import com.studyloop.backend.flashcard.FlashcardNotFoundException;
import com.studyloop.backend.flashcard.NoFlashcardMaterialException;
import com.studyloop.backend.quiz.NoQuizMaterialException;
import com.studyloop.backend.review.ReviewCardNotFoundException;
import com.studyloop.backend.quiz.QuizGenerationException;
import com.studyloop.backend.quiz.QuizNotFoundException;
import com.studyloop.backend.usage.QuotaExceededException;
import com.studyloop.backend.usage.RateLimitExceededException;
import com.studyloop.backend.usage.TokenBudgetExceededException;
import com.studyloop.backend.video.VideoDailyCapExceededException;
import com.studyloop.backend.video.VideoDisabledException;
import com.studyloop.backend.video.VideoJobNotFoundException;
import com.studyloop.backend.video.VideoJobRunningException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body has invalid fields.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail handleEmailTaken(EmailAlreadyRegisteredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Email already registered");
        return problem;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Authentication failed");
        return problem;
    }

    @ExceptionHandler(InvalidTokenException.class)
    ProblemDetail handleInvalidToken(InvalidTokenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Authentication failed");
        return problem;
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("User not found");
        return problem;
    }

    @ExceptionHandler(CourseNotFoundException.class)
    ProblemDetail handleCourseNotFound(CourseNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Course not found");
        return problem;
    }

    // Course exists but the caller isn't a member → 403, same shape as the method-security denial.
    @ExceptionHandler(NotACourseMemberException.class)
    ProblemDetail handleNotACourseMember(NotACourseMemberException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Access denied");
        return problem;
    }

    @ExceptionHandler(InviteNotFoundException.class)
    ProblemDetail handleInviteNotFound(InviteNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Invite not found");
        return problem;
    }

    @ExceptionHandler(InviteExpiredException.class)
    ProblemDetail handleInviteExpired(InviteExpiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.GONE, ex.getMessage());
        problem.setTitle("Invite expired");
        return problem;
    }

    @ExceptionHandler(InviteEmailMismatchException.class)
    ProblemDetail handleInviteEmailMismatch(InviteEmailMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Access denied");
        return problem;
    }

    // A member acting beyond their course role (e.g. a MEMBER issuing invites) → 403.
    @ExceptionHandler(InsufficientCourseRoleException.class)
    ProblemDetail handleInsufficientCourseRole(InsufficientCourseRoleException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Access denied");
        return problem;
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail handleDocumentNotFound(DocumentNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Document not found");
        return problem;
    }

    // Uploaded a non-PDF (only PDFs are ingestible today) → 415 Unsupported Media Type.
    @ExceptionHandler(UnsupportedDocumentTypeException.class)
    ProblemDetail handleUnsupportedDocumentType(UnsupportedDocumentTypeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problem.setTitle("Unsupported document type");
        return problem;
    }

    // These bytes are already in this course as another member's private note (16.3) → 409. The
    // one duplicate that cannot be resolved by handing back the existing document, because doing
    // so would disclose it.
    @ExceptionHandler(DuplicateDocumentException.class)
    ProblemDetail handleDuplicateDocument(DuplicateDocumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Already uploaded");
        return problem;
    }

    // Promoting or exporting a note the pipeline has not finished reading → 409. Not a failure;
    // the same request works once the note reaches READY.
    @ExceptionHandler(NoteNotReadyException.class)
    ProblemDetail handleNoteNotReady(NoteNotReadyException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Note not ready");
        return problem;
    }

    @ExceptionHandler(EmptyDocumentException.class)
    ProblemDetail handleEmptyDocument(EmptyDocumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("No file uploaded");
        return problem;
    }

    // The upload exceeded the configured multipart size limit → 413 Payload Too Large.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONTENT_TOO_LARGE, "The uploaded file is too large.");
        problem.setTitle("Upload too large");
        return problem;
    }

    // Asked for a summary of a document with no ingested text yet → 400. Wait for READY.
    @ExceptionHandler(NoSummaryMaterialException.class)
    ProblemDetail handleNoSummaryMaterial(NoSummaryMaterialException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Nothing to summarize");
        return problem;
    }

    // The model was unreachable or returned something unusable → 502, as with chat and quizzes.
    @ExceptionHandler(SummaryGenerationException.class)
    ProblemDetail handleSummaryGeneration(SummaryGenerationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "The summary could not be generated. Please try again.");
        problem.setTitle("Summary unavailable");
        return problem;
    }

    // A filesystem failure while storing/reading bytes — unexpected, so 500.
    @ExceptionHandler(DocumentStorageException.class)
    ProblemDetail handleDocumentStorage(DocumentStorageException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "The file could not be stored. Please try again.");
        problem.setTitle("Storage error");
        return problem;
    }

    @ExceptionHandler(ChatConversationNotFoundException.class)
    ProblemDetail handleChatConversationNotFound(ChatConversationNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Conversation not found");
        return problem;
    }

    // The chat provider is unconfigured or errored — an upstream/server-side problem, and we
    // don't leak the provider's raw message to the client.
    @ExceptionHandler(ChatException.class)
    ProblemDetail handleChat(ChatException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "The assistant is temporarily unavailable. Please try again.");
        problem.setTitle("Chat unavailable");
        return problem;
    }

    @ExceptionHandler(QuizNotFoundException.class)
    ProblemDetail handleQuizNotFound(QuizNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Quiz not found");
        return problem;
    }

    // Asked to quiz on documents that have no ingested content yet → 400 (fix by choosing READY docs).
    @ExceptionHandler(NoQuizMaterialException.class)
    ProblemDetail handleNoQuizMaterial(NoQuizMaterialException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("No quiz material");
        return problem;
    }

    // The model was unconfigured or produced unusable quiz output — an upstream/server-side
    // problem, so 502 and no raw provider detail leaked to the client.
    @ExceptionHandler(QuizGenerationException.class)
    ProblemDetail handleQuizGeneration(QuizGenerationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "The quiz could not be generated. Please try again.");
        problem.setTitle("Quiz generation failed");
        return problem;
    }

    @ExceptionHandler(ForumThreadNotFoundException.class)
    ProblemDetail handleForumThreadNotFound(ForumThreadNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Discussion not found");
        return problem;
    }

    @ExceptionHandler(ForumAnswerNotFoundException.class)
    ProblemDetail handleForumAnswerNotFound(ForumAnswerNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Reply not found");
        return problem;
    }

    // 409, not 403: the caller is allowed to accept answers, and this is not an answer that can
    // be accepted. See ForumAnswerAuthor.
    @ExceptionHandler(AssistantAnswerNotAcceptableException.class)
    ProblemDetail handleAssistantAnswerNotAcceptable(AssistantAnswerNotAcceptableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("That reply cannot become course material");
        return problem;
    }

    @ExceptionHandler(FlashcardNotFoundException.class)
    ProblemDetail handleFlashcardNotFound(FlashcardNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Flashcard not found");
        return problem;
    }

    // Asked to build cards from a document with no ingested content → 400 (pick a READY document).
    @ExceptionHandler(NoFlashcardMaterialException.class)
    ProblemDetail handleNoFlashcardMaterial(NoFlashcardMaterialException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("No flashcard material");
        return problem;
    }

    // The model was unconfigured or produced unusable flashcard output → 502, no raw detail leaked.
    @ExceptionHandler(FlashcardGenerationException.class)
    ProblemDetail handleFlashcardGeneration(FlashcardGenerationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "The flashcards could not be generated. Please try again.");
        problem.setTitle("Flashcard generation failed");
        return problem;
    }

    // Grading a card that isn't yours, or doesn't exist. Same 404 either way, so the queue
    // can't be used to probe whether a card id belongs to someone else.
    @ExceptionHandler(ReviewCardNotFoundException.class)
    ProblemDetail handleReviewCardNotFound(ReviewCardNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Review card not found");
        return problem;
    }

    // Phase 10's two guards. Both are 429 and both carry Retry-After, because the client's correct
    // response to either is the same: wait the stated time, then retry. What differs is how long
    // and why, so `reason` is a machine-readable field and the numbers travel with it — a UI that
    // showed "too many requests" for an exhausted daily allowance would send someone into a retry
    // loop that cannot succeed for hours.
    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Too many requests");
        problem.setProperty("reason", "RATE_LIMIT");
        problem.setProperty("limit", ex.getLimit());
        problem.setProperty("windowSeconds", ex.getWindow().toSeconds());
        problem.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
        return retryable(problem, ex);
    }

    @ExceptionHandler(TokenBudgetExceededException.class)
    ResponseEntity<ProblemDetail> handleTokenBudget(TokenBudgetExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("AI allowance used up");
        problem.setProperty("reason", "TOKEN_BUDGET");
        problem.setProperty("usedTokens", ex.getUsedTokens());
        problem.setProperty("limitTokens", ex.getLimitTokens());
        problem.setProperty("windowSeconds", ex.getWindow().toSeconds());
        problem.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
        return retryable(problem, ex);
    }

    // ResponseEntity rather than a bare ProblemDetail, only because Retry-After is a header and
    // there is no other way to set one from here.
    private static ResponseEntity<ProblemDetail> retryable(ProblemDetail problem,
                                                           QuotaExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(problem);
    }

    // ── Phase 21 · video generation ─────────────────────────────────────────────────────────

    @ExceptionHandler(VideoJobNotFoundException.class)
    ProblemDetail handleVideoJobNotFound(VideoJobNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Video not found");
        return problem;
    }

    // 503 rather than 404: the endpoint exists, the renderer does not. The distinction matters to
    // the person reading it — a 404 says "you typed it wrong" and this says "this installation
    // cannot do that yet", which is the truth and is fixable.
    @ExceptionHandler(VideoDisabledException.class)
    ProblemDetail handleVideoDisabled(VideoDisabledException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Video generation unavailable");
        problem.setProperty("reason", "VIDEO_UNAVAILABLE");
        return problem;
    }

    @ExceptionHandler(VideoJobRunningException.class)
    ProblemDetail handleVideoJobRunning(VideoJobRunningException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Video still rendering");
        return problem;
    }

    // The daily cap, and it says the number. A limit the person cannot see is indistinguishable
    // from a bug, and this one is deliberately low enough to be hit.
    @ExceptionHandler(VideoDailyCapExceededException.class)
    ProblemDetail handleVideoDailyCap(VideoDailyCapExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Daily video limit reached");
        problem.setProperty("reason", "VIDEO_DAILY_CAP");
        return problem;
    }

    // A @PreAuthorize check failing (e.g. non-admin hitting /admin/**) throws this.
    @ExceptionHandler(AuthorizationDeniedException.class)
    ProblemDetail handleAccessDenied(AuthorizationDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to access this resource.");
        problem.setTitle("Access denied");
        return problem;
    }
}
