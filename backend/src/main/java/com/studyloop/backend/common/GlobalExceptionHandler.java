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
import com.studyloop.backend.document.EmptyDocumentException;
import com.studyloop.backend.document.NoSummaryMaterialException;
import com.studyloop.backend.document.SummaryGenerationException;
import com.studyloop.backend.document.UnsupportedDocumentTypeException;
import com.studyloop.backend.flashcard.FlashcardGenerationException;
import com.studyloop.backend.flashcard.FlashcardNotFoundException;
import com.studyloop.backend.flashcard.NoFlashcardMaterialException;
import com.studyloop.backend.quiz.NoQuizMaterialException;
import com.studyloop.backend.review.ReviewCardNotFoundException;
import com.studyloop.backend.quiz.QuizGenerationException;
import com.studyloop.backend.quiz.QuizNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    // A @PreAuthorize check failing (e.g. non-admin hitting /admin/**) throws this.
    @ExceptionHandler(AuthorizationDeniedException.class)
    ProblemDetail handleAccessDenied(AuthorizationDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to access this resource.");
        problem.setTitle("Access denied");
        return problem;
    }
}
