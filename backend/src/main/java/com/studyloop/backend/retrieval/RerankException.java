package com.studyloop.backend.retrieval;

// A rerank call that did not produce a usable ranking. Never reaches a controller: RerankStage
// catches it and falls back to the fused order, because a slightly worse answer beats no answer.
public class RerankException extends RuntimeException {

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
