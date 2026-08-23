package com.studyloop.backend.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// The content-bearing words of a question, for the retrievers that match word by word.
//
// Phase 18.1 needs this and Postgres will not do it: `plainto_tsquery` tokenizes and drops
// stopwords for the lexeme index, but the trigram list matches whole words against the text with
// `<%`, one operator per term, so the terms have to exist as Java strings before the SQL is built.
// Handing that operator a whole question is the mistake to avoid — `word_similarity` compares the
// *entire* first argument against the best continuous extent of the second, so a fourteen-word
// question matches nothing anywhere and the list comes back empty for every query.
//
// Two rules, both cheap and both about noise rather than about grammar:
//
//   length     a three-letter word's trigram set is one or two trigrams, so it is similar to
//              half the corpus. Four is the shortest length at which a match means something.
//   stopwords  the scaffolding a question is built from — "what", "which", "does", "explain" —
//              appears in most chunks and would put every chunk in the candidate set at a score
//              nothing else could outrank.
//
// Longest first, capped: a question with a dozen content words gets its most distinctive ones,
// because each surviving term costs one index scan and one similarity evaluation per candidate.
final class QueryTerms {

    private QueryTerms() {
    }

    // Function words and question scaffolding four letters or longer. Shorter ones — "is", "of",
    // "the", "and", "how", "why" — need no list, because the length rule already removes them.
    //
    // Deliberately short, and deliberately not a general English stopword list: this corpus is a
    // data-structures textbook, where "list", "heap", "tree", "time" and "order" are the subject
    // matter. A borrowed list would remove half of what the questions are about.
    private static final Set<String> STOPWORDS = Set.of(
            "what", "which", "when", "where", "does", "done", "doing", "this", "that", "these",
            "those", "with", "from", "into", "onto", "they", "them", "their", "there", "here",
            "have", "having", "been", "being", "about", "would", "could", "should", "will",
            "must", "than", "then", "also", "such", "some", "other", "only", "very", "much",
            "more", "most", "each", "both", "over", "under", "after", "before", "while",
            "because", "upon", "between", "explain", "describe", "compare", "using", "used",
            "give", "tell", "show", "many", "make", "makes", "made", "were", "was");

    // Four, and the reasoning is in the class comment: below it a term's trigram set is too small
    // to distinguish anything.
    private static final int MIN_LENGTH = 4;

    // The distinctive words of `query`, longest first, at most `max` of them. Empty for a query
    // made entirely of scaffolding, which the caller reads as "no list to fuse" rather than as an
    // error — a question like "why is that?" has nothing a term index could match.
    static List<String> of(String query, int max) {
        if (query == null || query.isBlank() || max <= 0) {
            return List.of();
        }
        // Split on anything that is not a letter or a digit, so "O(1)" contributes nothing and
        // "LinearHashTable" survives whole. Lowercased because word_similarity is case sensitive
        // and the corpus is not written in the case a student types.
        Set<String> distinct = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= MIN_LENGTH && !STOPWORDS.contains(token)) {
                distinct.add(token);
            }
        }
        List<String> terms = new ArrayList<>(distinct);
        // Longest first — a stable sort, so two terms of the same length keep the order they were
        // typed in and the same question always produces the same SQL.
        terms.sort(Comparator.comparingInt(String::length).reversed());
        return terms.size() <= max ? List.copyOf(terms) : List.copyOf(terms.subList(0, max));
    }
}
