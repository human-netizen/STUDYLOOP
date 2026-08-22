package com.studyloop.backend.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 16.3's LaTeX export.
//
// This exists to make the storage decision defensible rather than merely convenient. Notes are
// stored as Markdown with `$...$` maths because the chunker, the `tsvector` and 11.2's KaTeX all
// want prose with the maths inline — but "we chose Markdown" is only an argument if the direction
// given up is still available. It is a converter, and these tests are what say it converts rather
// than mangles.
class LatexExportTest {

    @Test
    void headingsBecomeSections() {
        String tex = LatexExport.of("Week 4", """
                # Quicksort

                ## Partitioning

                ### Lomuto
                """);

        assertThat(tex).contains("\\section{Quicksort}");
        assertThat(tex).contains("\\subsection{Partitioning}");
        assertThat(tex).contains("\\subsubsection{Lomuto}");
    }

    @Test
    void theDocumentIsCompilableOnItsOwn() {
        String tex = LatexExport.of("Week 4", "Just a sentence.");

        assertThat(tex).startsWith("\\documentclass");
        assertThat(tex).contains("\\usepackage{amsmath,amssymb}");
        assertThat(tex).contains("\\title{Week 4}");
        assertThat(tex).contains("\\begin{document}");
        assertThat(tex).endsWith("\\end{document}\n");
    }

    @Test
    void mathsPassesThroughUntouchedWhileTheProseAroundItIsEscaped() {
        // The whole trick of the converter. Inside dollars the text is already LaTeX and escaping
        // it would print the source; outside them an underscore is a subscript that fails to
        // compile, and a percent sign silently swallows the rest of the line.
        String tex = LatexExport.of("N", "The bound is $O(n \\log n)$ for 50% of inputs, see x_1.");

        assertThat(tex).contains("$O(n \\log n)$");
        assertThat(tex).contains("50\\%");
        assertThat(tex).contains("x\\_1");
    }

    @Test
    void displayMathsSurvivesAsDisplayMaths() {
        String tex = LatexExport.of("N", "$$T(n) = 2T(n/2) + O(n)$$");

        assertThat(tex).contains("$$T(n) = 2T(n/2) + O(n)$$");
    }

    @Test
    void aLoneDollarSignIsAPriceRatherThanAnUnclosedEquation() {
        String tex = LatexExport.of("N", "It cost $5 to print.");

        // Not swallowed into an unterminated math environment, which would make the rest of the
        // note vanish from the rendered PDF with no error a reader would connect to it.
        assertThat(tex).contains("5 to print");
    }

    @Test
    void listsBecomeEnvironmentsAndAreClosed() {
        String tex = LatexExport.of("N", """
                - first
                - second

                After the list.
                """);

        assertThat(tex).contains("\\begin{itemize}");
        assertThat(tex).contains("\\item first");
        assertThat(tex).contains("\\item second");
        assertThat(tex).contains("\\end{itemize}");
        assertThat(tex.indexOf("\\end{itemize}")).isLessThan(tex.indexOf("After the list."));
    }

    @Test
    void numberedListsBecomeEnumerate() {
        String tex = LatexExport.of("N", """
                1. base case
                2. recursive case
                """);

        assertThat(tex).contains("\\begin{enumerate}");
        assertThat(tex).contains("\\item base case");
        assertThat(tex).contains("\\end{enumerate}");
    }

    @Test
    void aMarkdownTableBecomesTabular() {
        String tex = LatexExport.of("N", """
                | Operation | Cost |
                | --- | --- |
                | Insert | $O(1)$ |
                """);

        assertThat(tex).contains("\\begin{tabular}{ll}");
        assertThat(tex).contains("Operation & Cost \\\\");
        assertThat(tex).contains("\\midrule");
        // The maths inside a cell is still maths.
        assertThat(tex).contains("Insert & $O(1)$ \\\\");
        assertThat(tex).contains("\\end{tabular}");
    }

    @Test
    void codeBecomesVerbatimRatherThanEscapedProse() {
        String tex = LatexExport.of("N", """
                ```
                if (a_i < b_j) { swap(); }
                ```
                """);

        assertThat(tex).contains("\\begin{verbatim}");
        // Untouched inside verbatim — escaping it there would print the escapes.
        assertThat(tex).contains("if (a_i < b_j) { swap(); }");
        assertThat(tex).contains("\\end{verbatim}");
    }

    @Test
    void emphasisSurvives() {
        String tex = LatexExport.of("N", "This is **important** and this is *aside* and `code`.");

        assertThat(tex).contains("\\textbf{important}");
        assertThat(tex).contains("\\emph{aside}");
        assertThat(tex).contains("\\texttt{code}");
    }

    @Test
    void latexSpecialCharactersInProseAreNeutralised() {
        String tex = LatexExport.of("N", "Costs & fees, 100% done, #4, {braces}, ~tilde, ^caret");

        assertThat(tex).contains("\\&");
        assertThat(tex).contains("\\%");
        assertThat(tex).contains("\\#");
        assertThat(tex).contains("\\{braces\\}");
        assertThat(tex).contains("\\textasciitilde{}");
        assertThat(tex).contains("\\textasciicircum{}");
    }
}
