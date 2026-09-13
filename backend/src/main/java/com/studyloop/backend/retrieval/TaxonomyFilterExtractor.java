package com.studyloop.backend.retrieval;

import com.studyloop.backend.document.DocumentCategory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Phase 23.2 — reading a week and a kind of material out of the question itself.
//
// **Its own patterns rather than `DocumentTaxonomyInferrer`'s, on purpose.** A filename and a
// sentence are different languages and the abbreviations do not survive the crossing: `hw`,
// `pset`, `ch3` and `lec` are ordinary in a filename and would be false positives in prose, and
// `final` is a filename word and an everyday adjective ("what is the final complexity"). Sharing
// one pattern set would mean tuning it for whichever side broke last.
//
// **It never returns UNCLASSIFIED.** Nobody asks a question about the documents their instructor
// has not got round to labelling, so a filter that could mean UNCLASSIFIED would narrow a search
// to exactly the material the reader did not ask for — and would do it on a question that
// happened to contain no category word at all, which is most of them.
//
// This runs on every question and does nothing but match regexes, which is what pays for the rule
// in TaxonomyStage that the database is only asked when something here matched.
@Component
public class TaxonomyFilterExtractor {

    // "week 3", "week three", "wk 7". Digits and the spelled forms up to twelve, because a
    // fourteen-week course is spoken about in words as often as in figures and the spelled half
    // stops at the point where people go back to digits.
    private static final Map<String, Integer> SPELLED = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11),
            Map.entry("twelve", 12));

    // `(?![0-9a-z])` for the reason DocumentTaxonomyInferrer gives: the guard has to say "this
    // token does not continue", and `\b` says something subtly different that a digit or an
    // underscore can defeat.
    private static final Pattern WEEK = Pattern.compile(
            "\\b(?:week|wk)\\s*(?:of\\s+)?0*(\\d{1,2}|"
                    + String.join("|", SPELLED.keySet()) + ")(?![0-9a-z])",
            Pattern.CASE_INSENSITIVE);

    // Same precedence as the filename inferrer — most specific first, first hit wins — with the
    // abbreviations and the ambiguous words removed.
    private static final List<Rule> RULES = List.of(
            new Rule(DocumentCategory.EXAM, "exams?|midterms?|mid-?term|past\\s*papers?"),
            new Rule(DocumentCategory.LAB, "labs?|lab\\s*sessions?|practicals?"),
            new Rule(DocumentCategory.TUTORIAL, "tutorials?|recitations?"),
            new Rule(DocumentCategory.ASSIGNMENT, "assignments?|homework|problem\\s*sets?"),
            new Rule(DocumentCategory.READING, "readings?|textbooks?"),
            new Rule(DocumentCategory.LECTURE, "lectures?|slides?"));

    // A letter or a digit in any script — Bangla included, which is why this is `\p{L}` and not
    // `[a-z]`.
    private static final Pattern HAS_CONTENT = Pattern.compile("[\\p{L}\\p{N}]");

    private record Rule(DocumentCategory category, Pattern pattern) {

        Rule(DocumentCategory category, String alternatives) {
            this(category, Pattern.compile("\\b(?:" + alternatives + ")\\b",
                    Pattern.CASE_INSENSITIVE));
        }
    }

    public Extraction extract(String query) {
        if (query == null || query.isBlank()) {
            return new Extraction(TaxonomyFilter.NONE, query);
        }
        Integer week = week(query);
        DocumentCategory category = category(query);
        if (week == null && category == null) {
            return new Extraction(TaxonomyFilter.NONE, query);
        }
        return new Extraction(new TaxonomyFilter(week, category), residual(query, category));
    }

    // The question with the words that chose *where* to look taken out of it.
    //
    // **This is not tidiness, it is the difference between a narrowed search and a broken one.**
    // With `retrieval.stages.lexical-or` off — which is the shipped default — `plainto_tsquery`
    // AND-joins every content word, so a chunk must contain all of them. No chunk in any corpus
    // contains the word "week", so "what did week 3 say about linear probing" matches **nothing**
    // lexically: adding a routing phrase to a question deletes the entire sparse half of hybrid
    // retrieval, silently, and leaves the stub-noise dense half to carry it. The same phrase also
    // sits inside the embedded string, pulling the query vector toward material about weeks.
    //
    // So the phrase is used once, to choose the documents, and then it is spent. What is *not*
    // stripped is the question the cross-encoder reads and the question that is stored and shown:
    // 18.2's rule is that expansion is a way of finding candidates rather than a way of restating
    // the ask, and this is the same rule in the other direction.
    //
    // Stripping only ever runs when a filter came out, so a question that named no week and no
    // kind of material is returned unchanged, character for character.
    private static String residual(String query, DocumentCategory category) {
        String residue = WEEK.matcher(query).replaceAll(" ");
        if (category != null) {
            for (Rule rule : RULES) {
                if (rule.category() == category) {
                    residue = rule.pattern().matcher(residue).replaceAll(" ");
                    break;
                }
            }
        }
        // Commas and "from" are left where they are: the residue is a retrieval query, not prose,
        // and `plainto_tsquery` drops punctuation and stopwords itself.
        residue = residue.replaceAll("\\s+", " ").trim();
        // A question that was *only* a routing phrase — "week 3?" — has nothing left to search
        // for, and searching for nothing inside the right documents returns nothing at all. The
        // original is the better query there: the narrowing still applies, and the dense half at
        // least has a string to embed.
        //
        // The test is "nothing left that could match" rather than `isEmpty()`, because stripping
        // "week 3" out of "week 3?" leaves a question mark — not an empty string, and not a query
        // either.
        return HAS_CONTENT.matcher(residue).find() ? residue : query;
    }

    // What a question asked for, and what is left of it once that has been taken out.
    public record Extraction(TaxonomyFilter filter, String searchQuery) { }

    private static Integer week(String query) {
        Matcher matcher = WEEK.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        String captured = matcher.group(1).toLowerCase(Locale.ROOT);
        Integer spelled = SPELLED.get(captured);
        if (spelled != null) {
            return spelled;
        }
        int week = Integer.parseInt(captured);
        // Outside the column's range this is a number that happened to follow the word "week".
        // Dropped rather than sent, so the resolve query is never asked a question the check
        // constraint has already answered.
        return week >= 1 && week <= 52 ? week : null;
    }

    private static DocumentCategory category(String query) {
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(query).find()) {
                return rule.category();
            }
        }
        return null;
    }
}
