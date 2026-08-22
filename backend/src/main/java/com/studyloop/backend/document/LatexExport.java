package com.studyloop.backend.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Renders a digitised note back out as a LaTeX document (Phase 16.3).
//
// **This exists because the storage decision went the other way.** Notes are stored as Markdown
// with `$...$` maths, not as LaTeX: LaTeX is a rendering target, and a note kept in it would have
// to be un-typeset before the chunker could split it, the `tsvector` could index it or the answer
// view could show it. Every one of those wants prose with the maths inline, which is what Markdown
// is. But "we chose Markdown" is only defensible if nothing is lost by it — so the direction that
// was given up is provided as a converter, which is cheap, and the direction that was kept is the
// one the pipeline runs on, which is the one that has to be cheap.
//
// Deliberately a small converter and not a Markdown engine. It handles what the notes reader is
// instructed to emit — headings, lists, tables, fenced code, inline and display maths — and passes
// anything else through as escaped text. A note is a page of somebody's handwriting; there is no
// nested blockquote in it.
final class LatexExport {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\s*\\|[\\s:|-]+\\|\\s*$");

    private static final List<String> SECTIONS =
            List.of("section", "subsection", "subsubsection", "paragraph", "subparagraph", "subparagraph");

    private LatexExport() {
    }

    static String of(String title, String markdown) {
        StringBuilder out = new StringBuilder();
        out.append("""
                \\documentclass[11pt,a4paper]{article}
                \\usepackage[utf8]{inputenc}
                \\usepackage{amsmath,amssymb}
                \\usepackage{booktabs}
                \\usepackage{hyperref}
                """);
        out.append("\\title{").append(escape(title)).append("}\n");
        out.append("\\date{}\n\\begin{document}\n\\maketitle\n\n");
        out.append(body(markdown));
        out.append("\n\\end{document}\n");
        return out.toString();
    }

    private static String body(String markdown) {
        List<String> lines = List.of(markdown.split("\n", -1));
        StringBuilder out = new StringBuilder();
        Mode mode = Mode.TEXT;
        List<List<String>> table = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (line.strip().startsWith("```")) {
                mode = flush(out, mode, table);
                i = verbatim(out, lines, i);
                continue;
            }
            if (TABLE_ROW.matcher(line).matches()) {
                if (mode != Mode.TABLE) {
                    mode = flush(out, mode, table);
                    mode = Mode.TABLE;
                }
                if (!TABLE_RULE.matcher(line).matches()) {
                    table.add(cells(line));
                }
                continue;
            }
            if (mode == Mode.TABLE) {
                mode = flush(out, mode, table);
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                mode = flush(out, mode, table);
                out.append("\\").append(SECTIONS.get(heading.group(1).length() - 1))
                        .append('{').append(inline(heading.group(2))).append("}\n\n");
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);
            if (bullet.matches() || numbered.matches()) {
                Mode wanted = bullet.matches() ? Mode.ITEMIZE : Mode.ENUMERATE;
                if (mode != wanted) {
                    mode = flush(out, mode, table);
                    out.append("\\begin{").append(wanted.environment).append("}\n");
                    mode = wanted;
                }
                out.append("  \\item ")
                        .append(inline(bullet.matches() ? bullet.group(1) : numbered.group(1)))
                        .append('\n');
                continue;
            }
            if (line.isBlank()) {
                mode = flush(out, mode, table);
                out.append('\n');
                continue;
            }
            mode = flush(out, mode, table);
            out.append(inline(line)).append('\n');
        }
        flush(out, mode, table);
        return out.toString();
    }

    // Closes whichever environment is open. Returning the new mode rather than mutating a field
    // keeps this a pure function of the line stream, which is what makes it testable without a
    // document, a course or a model.
    private static Mode flush(StringBuilder out, Mode mode, List<List<String>> table) {
        switch (mode) {
            case ITEMIZE, ENUMERATE -> out.append("\\end{").append(mode.environment).append("}\n\n");
            case TABLE -> {
                out.append(tabular(table));
                table.clear();
            }
            default -> { }
        }
        return Mode.TEXT;
    }

    private static int verbatim(StringBuilder out, List<String> lines, int start) {
        out.append("\\begin{verbatim}\n");
        int i = start + 1;
        while (i < lines.size() && !lines.get(i).strip().startsWith("```")) {
            out.append(lines.get(i)).append('\n');
            i++;
        }
        out.append("\\end{verbatim}\n\n");
        return i;
    }

    private static List<String> cells(String line) {
        String trimmed = line.strip();
        trimmed = trimmed.substring(1, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("(?<!\\\\)\\|", -1)) {
            cells.add(cell.replace("\\|", "|").strip());
        }
        return cells;
    }

    private static String tabular(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder out = new StringBuilder();
        out.append("\\begin{tabular}{").append("l".repeat(columns)).append("}\n\\toprule\n");
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            List<String> rendered = new ArrayList<>(columns);
            for (int c = 0; c < columns; c++) {
                rendered.add(inline(c < row.size() ? row.get(c) : ""));
            }
            out.append(String.join(" & ", rendered)).append(" \\\\\n");
            if (r == 0) {
                out.append("\\midrule\n");
            }
        }
        return out.append("\\bottomrule\n\\end{tabular}\n\n").toString();
    }

    // Maths passes through untouched and everything around it is escaped. That split is the whole
    // trick: `\frac{1}{2}` inside dollars is already LaTeX and escaping it would print the source,
    // while an underscore in ordinary prose outside them is a subscript that fails to compile.
    private static String inline(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('$', i);
            if (open < 0) {
                out.append(escape(text.substring(i)));
                break;
            }
            out.append(escape(text.substring(i, open)));
            boolean display = text.startsWith("$$", open);
            String delimiter = display ? "$$" : "$";
            int close = text.indexOf(delimiter, open + delimiter.length());
            if (close < 0) {
                // An unbalanced dollar sign is a price, not an equation.
                out.append(escape(text.substring(open)));
                break;
            }
            out.append(text, open, close + delimiter.length());
            i = close + delimiter.length();
        }
        return emphasis(out.toString());
    }

    // Markdown's `**bold**` and `*italic*` survive escaping unharmed — the asterisk is not a LaTeX
    // special character — so they are translated after it rather than before.
    private static String emphasis(String text) {
        String bold = text.replaceAll("\\*\\*(.+?)\\*\\*", "\\\\textbf{$1}");
        String italic = bold.replaceAll("(?<!\\*)\\*([^*]+?)\\*(?!\\*)", "\\\\emph{$1}");
        return italic.replaceAll("`([^`]+?)`", "\\\\texttt{$1}");
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\textbackslash{}");
                case '&', '%', '#', '{', '}' -> out.append('\\').append(c);
                case '_' -> out.append("\\_");
                case '~' -> out.append("\\textasciitilde{}");
                case '^' -> out.append("\\textasciicircum{}");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private enum Mode {
        TEXT(""), ITEMIZE("itemize"), ENUMERATE("enumerate"), TABLE("");

        private final String environment;

        Mode(String environment) {
            this.environment = environment;
        }
    }
}
