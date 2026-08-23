package com.studyloop.backend.document;

// What language a document — or a question — is written in (Phase 19.1).
//
// **Two values, and the shortness of the list is the honest part.** The obvious alternative is an
// ISO 639-1 string, which would let the column hold `bn`, `en`, `hi`, `ar` and 180 others. Nothing
// in this application can produce those: detection here is a script count, so it can tell Bengali
// from Latin and cannot tell Hindi from Marathi or English from German. A column typed wider than
// its only writer is a column that will eventually be filled by a guess.
//
// The two are also not symmetric. ENGLISH is what every document was before this enum existed and
// what a document is when nothing says otherwise, so it is the fallback everywhere rather than a
// peer of BANGLA.
public enum Language {

    ENGLISH("English"),

    // Written in the Bengali script (Unicode U+0980–U+09FF). Called BANGLA rather than BENGALI
    // because that is what the language is called by the people this is being built for, and the
    // Unicode block name is the only place the other spelling appears.
    BANGLA("Bangla");

    // What the model is told to answer in. Naming the language in the prompt in English is
    // deliberate: an instruction written in the target language is one more thing the model can
    // read as content to be answered rather than as an instruction to be followed.
    private final String promptName;

    Language(String promptName) {
        this.promptName = promptName;
    }

    public String promptName() {
        return promptName;
    }
}
