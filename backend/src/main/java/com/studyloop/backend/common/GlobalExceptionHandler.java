package com.studyloop.backend.common;

import com.studyloop.backend.auth.EmailAlreadyRegisteredException;
import com.studyloop.backend.auth.InvalidCredentialsException;
import com.studyloop.backend.auth.InvalidTokenException;
import com.studyloop.backend.auth.UserNotFoundException;
import com.studyloop.backend.course.CourseNotFoundException;
import com.studyloop.backend.course.NotACourseMemberException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    // A @PreAuthorize check failing (e.g. non-admin hitting /admin/**) throws this.
    @ExceptionHandler(AuthorizationDeniedException.class)
    ProblemDetail handleAccessDenied(AuthorizationDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to access this resource.");
        problem.setTitle("Access denied");
        return problem;
    }
}
