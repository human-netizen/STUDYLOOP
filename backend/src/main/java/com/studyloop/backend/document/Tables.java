package com.studyloop.backend.document;

import java.util.ArrayList;
import java.util.List;

// Renders a grid of cells as a GitHub-flavoured Markdown table (Phase 16).
//
// Shared by the PPTX and DOCX extractors because both find real tables in their source and both
// have to produce the same thing the vision extractor already produces for a table on a page image.
// One renderer keeps that promise literally: a syllabus table has the same shape in the index
// whether it arrived as a slide, a Word document or a photograph of a printout.
//
// **Why a Markdown table rather than tab-separated text.** The chunker splits on blank lines, so a
// table written as loose rows survives, but the *reading* of it does not — "Insert O(log n)" as a
// line of text has lost which column each value was in. 11.2 renders Markdown tables in the answer
// view, so a cited table comes back looking like a table.
final class Tables {

    private Tables() {
    }

    static String markdown(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        // Markdown's grammar has no way to say "this table has no header", and a table whose rows
        // are ragged is not a table at all to a parser. The first row is treated as the header and
        // every row is padded to the widest, which is what a reader would do looking at it.
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append(row(rows.get(0), columns));
        out.append('\n').append(separator(columns));
        for (int i = 1; i < rows.size(); i++) {
            out.append('\n').append(row(rows.get(i), columns));
        }
        return out.toString();
    }

    private static String row(List<String> cells, int columns) {
        List<String> padded = new ArrayList<>(columns);
        for (int i = 0; i < columns; i++) {
            padded.add(escape(i < cells.size() ? cells.get(i) : ""));
        }
        return "| " + String.join(" | ", padded) + " |";
    }

    private static String separator(int columns) {
        return "| " + String.join(" | ", java.util.Collections.nCopies(columns, "---")) + " |";
    }

    // A pipe inside a cell ends the cell, and a newline inside one ends the table. Both occur in
    // real material — a complexity column reading "O(1) | amortized", a cell holding two lines of
    // prose — so both are neutralised rather than left to corrupt every row below them.
    private static String escape(String cell) {
        if (cell == null) {
            return "";
        }
        return cell.replace("|", "\\|").replaceAll("\\s+", " ").strip();
    }
}
