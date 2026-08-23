package com.studyloop.backend.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// Sentence boundaries, used by tier 2 to decide where to measure meaning and by tier 3 as the last
// boundary it is willing to cut on.
//
// **The English rule.** A full stop followed by whitespace followed by something that starts a
// sentence — a capital letter, an opening bracket, or a quotation mark. The second half of that is
// what makes it usable on technical prose without a table of abbreviations: "0.25 of the" does not
// split because `o` is lowercase, "Fig. 4 shows" does not split because `4` is not a letter, and
// "e.g. a treap" does not split for the same reason. It will still cut after "Prof." before a name,
// which costs a boundary in the wrong place and never costs a wrong answer.
//
// **The Bangla rule (Phase 19.3), and why the English one could not be stretched to cover it.**
// Bangla ends a sentence with the danda `।`, not a full stop, and Bangla has no capital letters at
// all. So *both halves* of the rule above fail on it, independently: there is no `.` to look
// behind, and even where a Bangla text does use one there is no `\p{Lu}` to look ahead to. The
// consequence was not a few misplaced boundaries — it was that a Bangla document of any length was
// exactly one sentence. Tier 2 needs six before it will run, so semantic splitting never fired on
// Bangla at all; tier 3's sentence rung was a no-op, so an oversized Bangla block fell straight
// through to a hard word cut, which is the one failure the whole adaptive chunker exists to remove.
//
// The danda needs no lookahead: nothing else uses that character, so unlike `.` it is never part of
// a number, an abbreviation or a filename. `\p{Lo}` is added to the English rule's lookahead for
// the mixed case — a Bangla text that writes `O(log n). এর` — and **it cannot change how any
// English document is split**, because no letter used to write English is in that category.
//
// Deliberately not a sentence-detection library. This is used to place chunk boundaries, where the
// consequence of a rare mistake is a slightly odd split, and a model dependency for that would be
// out of proportion.
final class Sentences {

    // Two alternatives: the English full stop with its "what starts a sentence" guard, and the
    // danda and double danda (U+0964, U+0965) on their own. The second alternative matches zero
    // characters when the danda ends a line, which splits immediately after it — the danda stays
    // with the sentence it terminates, as the full stop does.
    private static final Pattern BOUNDARY = Pattern.compile(
            "(?<=[.!?])[ \\t]+(?=[\\p{Lu}\\p{Lo}(\\[\"'])"
            + "|(?<=[।॥])[ \\t]*");

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
