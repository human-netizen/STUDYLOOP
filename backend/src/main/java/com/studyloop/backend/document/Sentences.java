package com.studyloop.backend.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// Sentence boundaries, used by tier 2 to decide where to measure meaning and by tier 3 as the last
// boundary it is willing to cut on.
//
// The rule is a full stop followed by whitespace followed by something that starts a sentence — a
// capital letter, an opening bracket, or a quotation mark. The second half of that is what makes it
// usable on technical prose without a table of abbreviations: "0.25 of the" does not split because
// `o` is lowercase, "Fig. 4 shows" does not split because `4` is not a letter, and "e.g. a treap"
// does not split for the same reason. It will still cut after "Prof." before a name, which costs a
// boundary in the wrong place and never costs a wrong answer.
//
// Deliberately not a sentence-detection library. This is used to place chunk boundaries, where the
// consequence of a rare mistake is a slightly odd split, and a model dependency for that would be
// out of proportion.
final class Sentences {

    private static final Pattern BOUNDARY = Pattern.compile("(?<=[.!?])[ \\t]+(?=[\\p{Lu}(\\[\"'])");

    private Sentences() {
    }

    static List<String> of(String text) {
        List<String> sentences = new ArrayList<>();
        for (String line : text.split("\\n")) {
            for (String candidate : BOUNDARY.split(line)) {
                String stripped = candidate.strip();
                if (!stripped.isEmpty()) {
                    sentences.add(stripped);
                }
            }
        }
        return sentences;
    }

    // The same split applied across units, with each sentence inheriting the page of the unit it
    // came out of. That is what keeps a chunk's page span honest after tier 2 or tier 3 has taken
    // a paragraph apart.
    static List<TextUnit> of(List<TextUnit> units) {
        List<TextUnit> sentences = new ArrayList<>();
        for (TextUnit unit : units) {
            for (String sentence : of(unit.text())) {
                sentences.add(new TextUnit(sentence, unit.page()));
            }
        }
        return sentences;
    }
}
