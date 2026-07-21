package com.studyloop.backend.document;

// A prepared chunk before it becomes a persisted DocumentChunk. pageNumber is where the
// chunk's first word came from; tokenCount is an approximation.
public record TextChunk(int index, Integer pageNumber, String content, int tokenCount) { }
