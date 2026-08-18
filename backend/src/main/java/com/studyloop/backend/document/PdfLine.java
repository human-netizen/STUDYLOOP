package com.studyloop.backend.document;

// One typeset line lifted off a PDF page, with the two properties that let a heading be told
// apart from a paragraph: how big it was set and whether it was bold.
//
// `y` is the baseline's distance from the top of the page, which is what makes vertical gaps
// comparable — the space between two lines of one paragraph is the leading, and the space before a
// new paragraph is visibly larger. That is the only paragraph signal a PDF actually carries; there
// are no paragraph marks in the file.
record PdfLine(int page, String text, float size, boolean bold, float y) {

    boolean isBlank() {
        return text.isBlank();
    }
}
