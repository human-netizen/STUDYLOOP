package com.studyloop.backend.document;

import org.springframework.stereotype.Component;

// Which of two languages a piece of text is written in (Phase 19.1), decided by counting scripts.
//
// **Why counting rather than a detector library.** Statistical language identification —
// Tika's, lingua's, CLD3's — exists to solve a problem this application does not have: telling
// apart languages that share an alphabet. Bangla and English do not share one. Every Bengali letter
// is in one Unicode script and every English letter is in another, so the signal is not
// probabilistic at all, and a model that returns "bn with confidence 0.97" is expressing
// uncertainty about something that is a fact of the encoding. A library here would be a megabyte of
// n-gram tables, a startup cost, and a dependency, in exchange for a worse answer on the only two
// languages it would ever be asked about.
//
// **What it cannot do, stated rather than hidden.** It cannot tell Bangla from Assamese — the two
// share a script and differ in two letters. It cannot tell English from German, French or Malay. It
// would call Hindi "English", because Devanagari is neither of the two scripts it counts, and the
// fallback is English. Every one of those is out of scope for a Bangladeshi course platform, and
// each would stay wrong if a probabilistic detector were used instead, because the enum has two
// values.
@Component
public class LanguageDetector {

    // The share of a text's *letters* that must be Bengali before it is a Bangla document.
    //
    // **The number sits in an empty region rather than on a boundary, which is the difference
    // between this and the trigram cut-off in 18.1.** Measured on the samples LanguageDetectorTest
    // prints: English prose 0.000, English prose quoting a Bangla term 0.054, a Bangla paragraph
    // written the way a Bangla computer-science text actually is — Bengali sentences carrying
    // English identifiers and complexity classes — 0.794, and Bangla prose 1.000. **Nothing real
    // lands between 0.054 and 0.794**, so any threshold inside that band decides the same cases,
    // and the choice of 0.20 is a lean rather than a boundary. It is placed at the low end because
    // the asymmetry runs that way: a Bangla document is defined by having Bangla in it, while an
    // English one is defined by being written in English, and a document that is a fifth Bengali
    // script is not an English document with a quotation in it.
    private static final double BANGLA_LETTER_SHARE = 0.20;

    // Scanning a whole book to decide one enum is work for no accuracy. A quarter of a million
    // characters is 60–100 pages, and a document whose first 100 pages are English is an English
    // document. The cap also bounds the cost of the one call this makes per ingestion.
    private static final int MAX_SCANNED_CHARS = 250_000;

    public Language detect(String text) {
        return banglaShare(text) >= BANGLA_LETTER_SHARE ? Language.BANGLA : Language.ENGLISH;
    }

    // Exposed because it is the thing worth asserting on. `detect` returns one of two values, so a
    // test of it can only ever say which side of the line a sample fell; the share is what shows
    // *how far* from the line, which is what makes the threshold above defensible rather than
    // stated.
    public double banglaShare(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int limit = Math.min(text.length(), MAX_SCANNED_CHARS);
        int letters = 0;
        int bengali = 0;
        // Codepoints rather than chars: Bengali is inside the basic plane so no surrogate pair can
        // appear in it, but emoji and mathematical alphanumerics in a mixed document can, and
        // walking those as two chars would count one symbol twice in the denominator.
        for (int i = 0; i < limit; ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            // Letters only, which is the normalization that makes the ratio mean anything. Digits,
            // punctuation, whitespace and formulae are shared between the two languages, so a page
            // of Bangla prose around a table of numbers would otherwise score as half English. It
            // also drops the Bengali vowel signs and the virama, which are combining marks rather
            // than letters: counting those would inflate every Bangla sample by roughly a third
            // against a threshold that was set without them.
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            letters++;
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.BENGALI) {
                bengali++;
            }
        }
        // A document with no letters at all — a page of pure formulae, a table of numbers, an
        // extraction that produced only page furniture — is not evidence of Bangla.
        return letters == 0 ? 0 : (double) bengali / letters;
    }
}
