package com.studyloop.backend.retrieval;

import com.studyloop.backend.retrieval.dto.SnippetPart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The extract-and-mark rules, tested directly. No Spring here: this is string handling, and the
// cases that matter (a match near the end of a long chunk, a purely semantic hit with nothing to
// mark) are tedious to arrange through the API and trivial to state here.
class SnippetsTest {

    @Test
    void termsDropStopwordsAndRepeats() {
        assertEquals(List.of("hnsw", "index"), Snippets.terms("What is the HNSW index and an index?"));
    }

    @Test
    void aQueryOfNothingButStopwordsHasNoTerms() {
        assertTrue(Snippets.terms("what is it for").isEmpty());
    }

    @Test
    void theMatchedWordComesBackMarkedAndTheRestDoesNot() {
        List<SnippetPart> parts = Snippets.of("Cosine distance orders the vector search.", List.of("cosine"));

        assertEquals(1, marked(parts).size());
        assertEquals("Cosine", marked(parts).get(0));
        assertEquals("Cosine distance orders the vector search.", plain(parts));
    }

    // Postgres stems the search itself; this covers the plural and -ing forms that make up
    // almost all of what a reader would expect to see highlighted.
    @Test
    void aPrefixLongEnoughToBeMeaningfulCountsAsAMatch() {
        List<SnippetPart> parts = Snippets.of("Indexing vectors, one vector per chunk.", List.of("index", "vectors"));

        assertEquals(List.of("Indexing", "vectors", "vector"), marked(parts));
    }

    // "in" must not light up "index", "into" and "integer".
    @Test
    void aShortPrefixDoesNotMatch() {
        assertTrue(marked(Snippets.of("Index into the integers.", List.of("in"))).isEmpty());
    }

    // The window has to follow the match. A chunk is several hundred words and the term that
    // brought it back is usually not in the first line.
    @Test
    void theWindowFollowsTheFirstMatchAndSaysItWasTrimmed() {
        String text = "alpha ".repeat(200) + "pgvector " + "omega ".repeat(200);

        String snippet = plain(Snippets.of(text, List.of("pgvector")));

        assertTrue(snippet.contains("pgvector"), () -> "the match must be in the window: " + snippet);
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…"), () -> "trimmed at both ends: " + snippet);
        assertTrue(snippet.length() < 300, () -> "a window, not the chunk: " + snippet.length());
    }

    // A semantic hit can share no vocabulary with the query at all. Showing the head of the chunk
    // with nothing marked is the honest rendering — there is no word to point at.
    @Test
    void aChunkWithNoLexicalMatchFallsBackToItsOpening() {
        List<SnippetPart> parts = Snippets.of("Recurrences unfold into trees of subproblems.", List.of("airline"));

        assertTrue(marked(parts).isEmpty());
        assertEquals(1, parts.size());
        assertTrue(plain(parts).startsWith("Recurrences"));
    }

    @Test
    void theWindowNeverOpensOrClosesMidWord() {
        String text = "extraordinarily ".repeat(40);

        String snippet = plain(Snippets.of(text, List.of("nothing")));

        for (String word : snippet.replace("…", "").trim().split(" ")) {
            assertEquals("extraordinarily", word, () -> "a word was cut in half: " + snippet);
        }
    }

    @Test
    void emptyContentYieldsNoParts() {
        assertTrue(Snippets.of("   ", List.of("anything")).isEmpty());
    }

    private static List<String> marked(List<SnippetPart> parts) {
        return parts.stream().filter(SnippetPart::match).map(SnippetPart::text).toList();
    }

    private static String plain(List<SnippetPart> parts) {
        return parts.stream().map(SnippetPart::text).reduce("", String::concat);
    }
}
