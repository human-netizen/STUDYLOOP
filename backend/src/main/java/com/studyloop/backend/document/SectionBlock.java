package com.studyloop.backend.document;

import java.util.ArrayList;
import java.util.List;

// A run of text under one heading path, before anything has decided whether it is one chunk or
// several. This is what the three tiers pass between each other: tier 1 produces blocks from
// headings, tier 2 produces them from meaning, tier 3 breaks up the ones that came out too big.
//
// `path` is the heading trail from the top of the document down to this block —
// ["Chapter 4", "4.2 Skiplists"] — which is both what a reader needs to know where a passage sits
// and what the embedded text carries as its context header (13.4).
record SectionBlock(List<String> path, List<TextUnit> units) {

    static final String PATH_SEPARATOR = " > ";

    SectionBlock {
        path = List.copyOf(path);
        units = List.copyOf(units);
    }

    static SectionBlock of(List<String> path, List<TextUnit> units) {
        return new SectionBlock(path, units);
    }

    boolean isEmpty() {
        return units.isEmpty();
    }

    // Paragraphs stay separated by a blank line, so a chunk read back out of the database looks
    // like the section it came from rather than one run-on paragraph.
    String text() {
        return String.join("\n\n", units.stream().map(TextUnit::text).toList());
    }

    String pathLabel() {
        return String.join(PATH_SEPARATOR, path);
    }

    // The document's own top-level section — the H1 this block sits under. Merging is allowed
    // within one of these and never across two, because two adjacent chapters are adjacent only in
    // the file.
    String topLevel() {
        return path.isEmpty() ? "" : path.get(0);
    }

    int pageStart() {
        return units.get(0).page();
    }

    int pageEnd() {
        int last = units.get(0).page();
        for (TextUnit unit : units) {
            last = Math.max(last, unit.page());
        }
        return last;
    }

    // A block holding the same text under the deepest heading path the two of them agree on. Two
    // sibling subsections merge under their shared parent; claiming the first one's path would file
    // the second one's text under a heading it does not belong to.
    SectionBlock mergedWith(SectionBlock next) {
        List<TextUnit> combined = new ArrayList<>(units);
        combined.addAll(next.units());
        return new SectionBlock(commonPrefix(path, next.path()), combined);
    }

    private static List<String> commonPrefix(List<String> left, List<String> right) {
        List<String> prefix = new ArrayList<>();
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            if (!left.get(i).equals(right.get(i))) {
                break;
            }
            prefix.add(left.get(i));
        }
        return prefix;
    }

    SectionBlock withUnits(List<TextUnit> replacement) {
        return new SectionBlock(path, replacement);
    }
}
