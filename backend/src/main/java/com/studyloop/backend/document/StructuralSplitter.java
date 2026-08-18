package com.studyloop.backend.document;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Tier 1 of the boundary ladder: cut where the author put a heading (Phase 13.2).
//
// This is the tier that does almost all of the work, because a lecture PDF or a textbook chapter
// already states its own structure — the only reason the old chunker could not use it is that
// extraction threw the structure away before the chunker saw the text. Given Markdown, finding the
// boundaries is not a heuristic at all: they are written down.
//
// Cost: zero. No model, no embedding, no second pass over the document.
@Component
public class StructuralSplitter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    // Markdown pages in, sections out, in document order. The heading itself leads the section it
    // opens: a chunk that starts "4.2 Skiplists" says what it is about even after it has been
    // pulled out of the book and dropped into a prompt next to five strangers.
    public List<SectionBlock> split(List<PageText> pages) {
        List<SectionBlock> blocks = new ArrayList<>();
        List<Heading> open = new ArrayList<>();
        List<TextUnit> units = new ArrayList<>();

        for (PageText page : pages) {
            for (String paragraph : paragraphsOf(page.text())) {
                Matcher heading = HEADING.matcher(paragraph);
                if (!heading.matches()) {
                    units.add(new TextUnit(paragraph, page.pageNumber()));
                    continue;
                }
                // A heading closes whatever was open and opens the next section.
                addIfPresent(blocks, pathOf(open), units);
                units = new ArrayList<>();
                descend(open, heading.group(1).length(), heading.group(2));
                units.add(new TextUnit(heading.group(2), page.pageNumber()));
            }
        }
        addIfPresent(blocks, pathOf(open), units);
        return blocks;
    }

    // Whether the document told us anything about its own structure. False means every boundary in
    // it would have to be invented, which is tier 2's job rather than this one's.
    public boolean isStructured(List<SectionBlock> blocks) {
        return blocks.stream().anyMatch(block -> !block.path().isEmpty());
    }

    // A section holding nothing but its own heading is dropped, not kept as a one-line chunk. That
    // happens constantly — "Chapter 4" immediately followed by "4.1 Introduction" — and the heading
    // is not lost, because it stays in the path of everything underneath it.
    private static void addIfPresent(List<SectionBlock> blocks, List<String> path, List<TextUnit> units) {
        if (units.size() == 1 && !path.isEmpty() && units.get(0).text().equals(path.get(path.size() - 1))) {
            return;
        }
        if (!units.isEmpty()) {
            blocks.add(SectionBlock.of(path, units));
        }
    }

    // The open heading trail, kept with each entry's own level rather than by depth alone.
    //
    // Depth alone is wrong whenever a document skips a level, and real books skip constantly: this
    // chapter opens "Chapter 4" at `##`, "Skiplists" at `#`, then every section at `###` with no
    // `##` anywhere. Truncating a two-deep trail to `level - 1` entries leaves both of them in
    // place, so the next `###` lands *under* the previous `###` — and 4.4 gets filed as a
    // subsection of 4.1. Popping by level instead makes siblings siblings whatever depth they sit
    // at, which is what the heading numbers say and what a reader would expect.
    private static void descend(List<Heading> open, int level, String text) {
        while (!open.isEmpty() && open.get(open.size() - 1).level() >= level) {
            open.remove(open.size() - 1);
        }
        open.add(new Heading(level, text));
    }

    private static List<String> pathOf(List<Heading> open) {
        return open.stream().map(Heading::text).toList();
    }

    private record Heading(int level, String text) { }

    // Blank-line separated blocks, which is what the extractor emits and what Markdown means by a
    // paragraph. A heading is always its own block because the extractor puts blank lines around it.
    private static List<String> paragraphsOf(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String candidate : text.split("\\n\\s*\\n")) {
            String stripped = candidate.strip();
            if (!stripped.isEmpty()) {
                paragraphs.add(stripped);
            }
        }
        return paragraphs;
    }
}
