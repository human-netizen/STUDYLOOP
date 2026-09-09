package com.studyloop.backend.flashcard;

import java.util.ArrayList;
import java.util.List;

// Renders a member's deck as the CSV Anki imports (Phase 29.1).
//
// **Nothing has ever left this application.** Cards are generated here, reviewed here and deleted
// here, which is a reasonable thing to build and an unreasonable thing to be stuck inside: a
// student who cannot get a term's cards out is a student who will not put a term's cards in.
//
// Anki is the target because it is the format the audience already has, and because it costs one
// file. A two-column CSV imports natively; `.apkg` is a SQLite container and would be a schema,
// a writer and a dependency for exactly one feature.
//
// **CSV is a format, not a `String.join(",")`.** Card text is model-generated prose about lecture
// material: it has commas in every other sentence, quotation marks around defined terms, and
// newlines wherever the answer is a list. RFC 4180 is the specification that says how those
// survive the round trip, and the whole of it that matters here is two rules — quote the field,
// and double any quote inside it.
final class FlashcardCsv {

    // Anki reads leading `#` lines as import settings rather than as cards, so the file says how
    // to read itself instead of relying on the importer's defaults being what we assumed.
    private static final List<String> DIRECTIVES = List.of(
            "#separator:Comma",
            "#html:false",
            "#columns:Front,Back");

    // RFC 4180 §2.1 specifies CRLF between records, and it is also what Excel writes. Anki's
    // reader takes either, so the standard one wins.
    private static final String RECORD_SEPARATOR = "\r\n";

    private FlashcardCsv() {
    }

    // No UTF-8 BOM, deliberately. A BOM is what makes Excel's double-click path read Bangla cards
    // correctly instead of as mojibake — but it also puts a stray code point in front of the first
    // `#separator` line, and whether Anki still recognises the directive after it is a guess.
    // The named consumer wins over the convenient one; Excel's own "From Text/CSV" import reads
    // UTF-8 without the BOM.
    static String of(List<Flashcard> cards) {
        List<String> lines = new ArrayList<>(DIRECTIVES);
        for (Flashcard card : cards) {
            lines.add(field(card.getFront()) + "," + field(card.getBack()));
        }
        // Trailing separator: a final record ends with one, so the file does not read as though it
        // was truncated mid-write.
        return String.join(RECORD_SEPARATOR, lines) + RECORD_SEPARATOR;
    }

    // RFC 4180 §2.5-2.7. Quote when the field holds the delimiter, a quote or a line break, and
    // double every quote inside a quoted field.
    //
    // Leading and trailing spaces are quoted too, which the RFC does not require: unquoted, a
    // reader is entitled to strip them, and `"  x"` and `"x"` are different faces of a card.
    //
    // **What this deliberately does not do is neutralise formulas.** A field opening with `=`,
    // `+`, `-` or `@` is executed by a spreadsheet, and the usual mitigation is to prefix it with
    // an apostrophe. That mitigation cannot be applied here: card backs are Markdown and a great
    // many of them legitimately open with `-`, so the guard would put a visible apostrophe on the
    // face of every list card in Anki — corrupting the format this file exists to produce, to
    // protect a reader who opened their own export of their own cards in a spreadsheet. The risk
    // is recorded rather than traded for.
    private static String field(String value) {
        String text = value == null ? "" : value;
        if (text.isEmpty()) {
            return text;
        }
        boolean quoted = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0
                || text.charAt(0) == ' '
                || text.charAt(text.length() - 1) == ' ';
        return quoted ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }
}
