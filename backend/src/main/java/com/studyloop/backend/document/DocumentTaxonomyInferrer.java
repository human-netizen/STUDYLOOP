package com.studyloop.backend.document;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Phase 23.2 — reading a week number and a category off a filename, and nothing else.
//
// **This is a default, not a classification, and the distinction is the whole design.** The
// correction path (`DocumentTaxonomyService.update`) was built before this class, so an inferred
// value is a starting point a person overwrites rather than an answer they have to argue with.
// That is what makes a cheap heuristic defensible where a confident one would not be.
//
// **Why not ask a model.** A classifier call per upload is one request, a few hundred tokens, and
// it would be more accurate than this on a badly named file. It is refused on the same grounds
// Phase 14 was switched off on: the cost is real and recurring, and the measurable benefit is the
// share of uploads whose filename says nothing — where the model would be classifying from the
// *content*, which is a different and much larger call. A course whose files are named
// `Week 03 - Hashing.pdf` gets the right answer here for free, and a course whose files are named
// `scan_0012.pdf` gets `UNCLASSIFIED` and a person with a dropdown, which is the correct outcome
// rather than a degraded one.
//
// **Every pattern is word-bounded, and that is not tidiness.** `lab` is a substring of `syllabus`
// and of `collaborative`; `tut` is a substring of `institute`. A contains-check would file the
// course syllabus as a lab session, and nothing downstream would ever say so — it would present
// as a question about week 1 quietly retrieving the wrong four documents.
@Component
public class DocumentTaxonomyInferrer {

    // "Week 3", "week-03", "wk 7", "Week_12" — the separator is optional and leading zeros are
    // dropped by the capture. Bounded 1..52 by the column's check constraint; anything outside
    // that is read as a number that happens to follow the word and is discarded here rather than
    // rejected at the database.
    //
    // **The trailing guard is `(?![0-9])` and not `\b`, which is a real defect this caught rather
    // than a nicety.** `Week_03_Hashing.pptx` is a perfectly ordinary filename, and an underscore
    // is a *word* character — so `\b` between the `3` and the `_` does not hold and the whole
    // match fails, silently, on a name that says exactly what week it is. What the guard actually
    // has to say is "the number does not continue", which is what stops `week 123` reading as
    // week 12.
    private static final Pattern WEEK = Pattern.compile(
            "\\b(?:week|wk)[\\s._-]*0*(\\d{1,2})(?![0-9])", Pattern.CASE_INSENSITIVE);

    // Ordered most specific first and matched in order, because a file called
    // "Lab 4 - Assignment brief.pdf" is a lab whose brief is the assignment, and one called
    // "Midterm revision lecture.pptx" is about the exam. The first hit wins, so this list is a
    // precedence rule and not a set.
    private static final List<Rule> RULES = List.of(
            new Rule(DocumentCategory.EXAM, "exam|midterm|mid-?term|finals?|past\\s*paper"),
            new Rule(DocumentCategory.LAB, "lab|labs|practical"),
            new Rule(DocumentCategory.TUTORIAL, "tutorial|tut|recitation"),
            new Rule(DocumentCategory.ASSIGNMENT,
                    "assignment|homework|hw|problem\\s*set|pset|coursework"),
            new Rule(DocumentCategory.READING, "reading|readings|textbook|chapter|ch\\d+"),
            new Rule(DocumentCategory.LECTURE, "lecture|lec|slides|deck|handout"));

    private record Rule(DocumentCategory category, String alternatives) {

        // Leading `\b` so `lab` inside `syllabus` is not a lab; trailing `(?![a-z])` rather than a
        // second `\b`, because `tut6`, `hw2` and `lab3` are how people name files and a digit is a
        // word character — `\b` refuses every one of them. The two ends are asymmetric because
        // they guard against different things: a keyword swallowed by a longer word, and a
        // keyword followed by the number that says which one.
        Pattern pattern() {
            return Pattern.compile("\\b(?:" + alternatives + ")(?![a-z])",
                    Pattern.CASE_INSENSITIVE);
        }
    }

    // Compiled once. `Rule.pattern()` builds a Pattern and this class is a singleton asked to do
    // this on every upload, so the compilation happens at class-init rather than per call.
    private static final List<CompiledRule> COMPILED = RULES.stream()
            .map(rule -> new CompiledRule(rule.category(), rule.pattern()))
            .toList();

    private record CompiledRule(DocumentCategory category, Pattern pattern) { }

    // What the filename says, with the parts it does not say left as "nobody has said".
    public InferredTaxonomy infer(String filename) {
        if (filename == null || filename.isBlank()) {
            return InferredTaxonomy.NOTHING;
        }
        String name = filename.toLowerCase(Locale.ROOT);
        return new InferredTaxonomy(week(name), category(name));
    }

    private static Integer week(String name) {
        Matcher matcher = WEEK.matcher(name);
        if (!matcher.find()) {
            return null;
        }
        int week = Integer.parseInt(matcher.group(1));
        return week >= 1 && week <= 52 ? week : null;
    }

    private static DocumentCategory category(String name) {
        for (CompiledRule rule : COMPILED) {
            if (rule.pattern().matcher(name).find()) {
                return rule.category();
            }
        }
        return DocumentCategory.UNCLASSIFIED;
    }

    // Null week means the filename did not say, which is a different statement from "week 0" and
    // is stored as the same null the column defaults to.
    public record InferredTaxonomy(Integer week, DocumentCategory category) {

        public static final InferredTaxonomy NOTHING =
                new InferredTaxonomy(null, DocumentCategory.UNCLASSIFIED);
    }
}
