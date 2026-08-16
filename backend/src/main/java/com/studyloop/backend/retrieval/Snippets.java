package com.studyloop.backend.retrieval;

import com.studyloop.backend.retrieval.dto.SnippetPart;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Turns a retrieved chunk into the short, marked-up extract a search result shows.
//
// Two jobs. Pick a window — a chunk is several hundred tokens, and a result list that prints all
// of it is a wall of text — and mark the words the query matched inside it, so the reader can see
// at a glance why this passage came back.
//
// The window is centred on the FIRST matching word rather than on the start of the chunk. A
// semantic hit often matches nothing lexically at all; that case falls back to the head of the
// chunk, which is honest: there is no word to point at.
final class Snippets {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    // Roughly two lines at the width the result list renders at.
    private static final int WINDOW = 260;
    // How much context to keep in front of the first match, so it isn't flush against the ellipsis.
    private static final int LEAD = 60;
    // Two long queries in a row shouldn't turn the page into a highlighter accident.
    private static final int MAX_TERMS = 12;
    // A prefix shorter than this matches too much: "in" would light up "index", "into", "integer".
    private static final int MIN_PREFIX = 4;

    // The words Postgres' `english` text-search configuration drops, trimmed to the ones that
    // actually turn up in questions. Full parity isn't the goal — this list only decides what gets
    // highlighted, and highlighting "the" in every passage is the failure being avoided.
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do", "does", "for",
            "from", "how", "in", "into", "is", "it", "its", "of", "on", "or", "that", "the",
            "their", "then", "there", "these", "they", "this", "to", "was", "what", "when",
            "where", "which", "who", "why", "will", "with");

    private Snippets() {
    }

    // The query's content words, lowercased and de-duplicated in the order they were typed.
    static List<String> terms(String query) {
        if (query == null) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < MAX_TERMS) {
            String word = matcher.group();
            if (word.length() >= 2 && !STOPWORDS.contains(word)) {
                terms.add(word);
            }
        }
        return List.copyOf(terms);
    }

    // The extract, split into plain and matched runs. Empty content yields an empty list rather
    // than a part holding an empty string.
    static List<SnippetPart> of(String content, List<String> terms) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return List.of();
        }

        Matcher matcher = WORD.matcher(text);
        int firstMatch = -1;
        while (matcher.find()) {
            if (matches(matcher.group(), terms)) {
                firstMatch = matcher.start();
                break;
            }
        }

        // Snap both ends to word boundaries so the extract never opens or closes mid-word.
        int start = firstMatch < 0 ? 0 : wordStart(text, Math.max(0, firstMatch - LEAD));
        int end = Math.min(text.length(), start + WINDOW);
        if (end < text.length()) {
            end = wordEnd(text, end, start);
        }

        List<SnippetPart> parts = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        if (start > 0) {
            plain.append('…');
        }
        int cursor = start;
        matcher.region(start, end);
        while (matcher.find()) {
            if (!matches(matcher.group(), terms)) {
                continue;
            }
            plain.append(text, cursor, matcher.start());
            flush(parts, plain);
            parts.add(new SnippetPart(matcher.group(), true));
            cursor = matcher.end();
        }
        plain.append(text, cursor, end);
        if (end < text.length()) {
            plain.append('…');
        }
        flush(parts, plain);
        return parts;
    }

    // A word matches when it equals a term, or when either is a prefix of the other and the
    // shorter one is long enough to be meaningful — "index" finds "indexing", "vectors" finds
    // "vector". Postgres does the real stemming for the search itself (Snowball, in
    // plainto_tsquery); re-implementing that in Java purely to decide what to bold is not worth
    // the dependency, and prefixes cover the plural and -ing cases that make up almost all of it.
    private static boolean matches(String word, List<String> terms) {
        String lower = word.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (lower.equals(term)) {
                return true;
            }
            String shorter = lower.length() < term.length() ? lower : term;
            String longer = lower.length() < term.length() ? term : lower;
            if (shorter.length() >= MIN_PREFIX && longer.startsWith(shorter)) {
                return true;
            }
        }
        return false;
    }

    // Forward to the start of the next whole word — but only when `index` actually landed inside
    // one. Advancing from a position that is already a word start would push the window past the
    // very match it was centred on.
    private static int wordStart(String text, int index) {
        if (index <= 0) {
            return 0;
        }
        int i = index;
        if (isWordChar(text.charAt(i - 1))) {
            while (i < text.length() && isWordChar(text.charAt(i))) {
                i++;
            }
        }
        while (i < text.length() && !isWordChar(text.charAt(i))) {
            i++;
        }
        return i;
    }

    // Back to the end of the last whole word, never past `floor` (the window's own start).
    private static int wordEnd(String text, int index, int floor) {
        int i = index;
        while (i > floor && isWordChar(text.charAt(i))) {
            i--;
        }
        return i;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private static void flush(List<SnippetPart> parts, StringBuilder plain) {
        if (plain.length() > 0) {
            parts.add(new SnippetPart(plain.toString(), false));
            plain.setLength(0);
        }
    }
}
