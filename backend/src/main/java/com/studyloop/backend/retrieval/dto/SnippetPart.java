package com.studyloop.backend.retrieval.dto;

// One run of a snippet: either plain text or a run the query matched. The split is done on the
// server rather than shipping the query terms down and re-finding them in the browser, because
// the matching rule (see Snippets) would then have to exist twice and stay identical in two
// languages. The client just renders `match` runs differently.
public record SnippetPart(String text, boolean match) {
}
