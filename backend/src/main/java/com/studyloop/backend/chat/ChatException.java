package com.studyloop.backend.chat;

// Raised when answer generation fails (no key configured or a provider error). Surfaced to the
// caller as a 502-class error by the global exception handler.
public class ChatException extends RuntimeException {

    public ChatException(String message) {
        super(message);
    }

    public ChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
