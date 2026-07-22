package com.studyloop.backend.quiz;

// The model failed to produce a usable quiz (unconfigured provider, request error, or output
// that didn't parse into valid questions) → 502 (see GlobalExceptionHandler). Distinct from
// NoQuizMaterialException, which is the caller's fault (nothing to quiz on).
public class QuizGenerationException extends RuntimeException {

    public QuizGenerationException(String message) {
        super(message);
    }

    public QuizGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
