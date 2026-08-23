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
// **Phase 19.2 fixed a splitting rule that silently destroyed Bangla.** The token boundary used to
// be "anything that is not a letter or a digit", and a Bengali vowel sign is neither — it is a
// combining mark. So every word in a Bangla question was shredded at each of its vowels:
// "কুইকসর্টের" became five fragments, four of them under the length floor, and this method returned
// one meaningless syllable for a whole question. The trigram list that 18.1 added, and that Phase
// 19's plan expected to carry most of the weight for a language Postgres cannot stem, was therefore
// returning nothing for nine Bangla questions in ten. Marks now count as part of a word, which
// cannot change any English question: accented Latin is stored precomposed, so English text has
// essentially no combining marks in it to reclassify.
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
    //
    // It counts codepoints, which means it is a slightly weaker filter in Bangla than in English —
    // "কীভাবে" is six codepoints and three base letters. That is the right direction to be wrong
    // in: the floor exists to stop a two-trigram term matching half the corpus, and a Bengali
    // string of six codepoints has six trigrams whatever its letters are doing.
    private static final int MIN_LENGTH = 4;

    // The distinctive words of `query`, longest first, at most `max` of them. Empty for a query
    // made entirely of scaffolding, which the caller reads as "no list to fuse" rather than as an
    // error — a question like "why is that?" has nothing a term index could match.
    static List<String> of(String query, int max) {
        if (query == null || query.isBlank() || max <= 0) {
            return List.of();
        }
        // Split on anything that is not a letter, a digit or a combining mark, so "O(1)"
        // contributes nothing, "LinearHashTable" survives whole, and a Bengali word survives with
        // its vowel signs attached instead of being cut at each of them. Lowercased because
        // word_similarity is case sensitive and the corpus is not written in the case a student
        // types — a no-op for Bangla, which has no case.
        Set<String> distinct = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}\\p{M}]+")) {
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
