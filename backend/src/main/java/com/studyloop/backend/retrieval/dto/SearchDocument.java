package com.studyloop.backend.retrieval.dto;

import com.studyloop.backend.document.DocumentSource;

import java.util.List;
import java.util.UUID;

// Every hit that landed in one document, so a lecture that matched five times is one result with
// five passages rather than five results competing for the reader's attention.
//
// `source` says whether there is a file to open: UPLOAD hits open the PDF at `pageNumber`, FORUM
// hits are accepted answers written back into the corpus and have nothing to open.
public record SearchDocument(
        UUID documentId,
        String filename,
        DocumentSource source,
        int hitCount,
        List<SearchHit> hits
) {
}
