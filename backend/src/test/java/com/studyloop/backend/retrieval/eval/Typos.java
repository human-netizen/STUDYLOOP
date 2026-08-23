package com.studyloop.backend.retrieval.eval;

import java.util.Locale;

// Phase 18.4 — misspells a golden question, deterministically, so the trigram stage can be measured
// against the failure it exists for.
//
// **Without this the stage cannot be measured at all.** Every question in the golden set is spelled
// correctly, so an A/B on `stages.trigram` over that set compares a pipeline against itself plus a
// list that mostly re-finds what the lexeme list already found — which can show a cost and can
// never show the benefit. The benefit only exists on input the set does not contain, so the set has
// to be perturbed rather than the stage judged on the wrong population.
//
// **One transposed pair of letters in one word**, and each part of that is chosen rather than
// convenient:
//
//   one word     the minimum perturbation that produces the failure. `plainto_tsquery` ANDs its
//                terms, so a single unmatchable lexeme empties the entire sparse ranking — the
//                lexical half of hybrid retrieval does not degrade under a typo, it switches off.
//   the longest  the word carrying the most meaning, and the one a student is most likely to
//                mistype. Misspelling "the" would prove nothing.
//   transposed   the most common real typing error, and the hardest case for trigram matching: a
//                swap destroys two trigrams where a doubled letter destroys one. Measured on this
//                corpus, transpositions score around 0.556 against the correct word while doubled
//                letters score 0.80+, so this is the class nearest the matching threshold.
//
// Deterministic, because a harness whose input changes between runs cannot compare two runs.
final class Typos {

    // Shorter than this and a transposition is not a typo, it is a different word — and its trigram
    // set is too small for the match to mean anything either way.
    private static final int MIN_LENGTH = 6;

    private Typos() {
    }

    // The question with its longest word misspelled, or unchanged when it has no word long enough
    // to misspell — which is honest rather than a failure: a question of short words is one this
    // perturbation has nothing to say about, and forcing a typo into it would measure something
    // else.
    static String inject(String question) {
        String[] tokens = question.split(" ", -1);
        int target = -1;
        int best = MIN_LENGTH - 1;
        for (int i = 0; i < tokens.length; i++) {
            int length = lettersIn(tokens[i]);
            // Strictly greater, so the *first* longest word wins and the choice cannot depend on
            // iteration details.
            if (length > best) {
                best = length;
                target = i;
            }
        }
        if (target < 0) {
            return question;
        }
        tokens[target] = transpose(tokens[target]);
        return String.join(" ", tokens);
    }

    // Swaps the two letters either side of the word's midpoint, so the typo lands inside the word
    // rather than at an edge where a trigram match barely notices it.
    //
    // Letter *positions*, not character positions, so a hyphen or a bracket is never one of the two
    // things swapped. "red-black" has to become "red-lback" and not "redb-lack": moving a letter
    // across a hyphen makes two different words, which is a different perturbation with a different
    // effect on both retrievers, and this run is about one of them.
    private static String transpose(String token) {
        char[] characters = token.toCharArray();
        int[] letters = new int[characters.length];
        int count = 0;
        for (int i = 0; i < characters.length; i++) {
            if (Character.isLetter(characters[i])) {
                letters[count++] = i;
            }
        }
        // Transposing two identical letters produces the word back, so "running" would come out of
        // this unchanged and the question would quietly not be misspelled at all — a hole in the
        // perturbation that nothing downstream could see, because an unmisspelled question retrieves
        // perfectly well. Walk outward from the midpoint to the nearest pair that actually differs.
        int pair = adjacentDifferingPair(characters, letters, count);
        if (pair < 0) {
            return token;
        }
        int first = letters[pair];
        int second = letters[pair + 1];
        char swap = characters[first];
        characters[first] = characters[second];
        characters[second] = swap;
        return new String(characters);
    }

    // The index into `letters` of the first of two adjacent letters that are not the same, searched
    // outward from the word's midpoint so the typo stays as near the middle as it can. -1 for a word
    // of one repeated letter, which is left alone.
    private static int adjacentDifferingPair(char[] characters, int[] letters, int count) {
        int middle = count / 2 - 1;
        for (int offset = 0; offset < count; offset++) {
            for (int candidate : new int[] {middle - offset, middle + offset}) {
                if (candidate >= 0 && candidate < count - 1
                        && characters[letters[candidate]] != characters[letters[candidate + 1]]) {
                    return candidate;
                }
            }
        }
        return -1;
    }

    // Letters only, so "algorithm?" is measured as nine and the trailing punctuation is left where
    // it was.
    private static int lettersIn(String token) {
        int count = 0;
        for (char c : token.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetter(c)) {
                count++;
            }
        }
        return count;
    }
}
