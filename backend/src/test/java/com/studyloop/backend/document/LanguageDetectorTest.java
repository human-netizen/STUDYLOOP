package com.studyloop.backend.document;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 19.1. The assertions that matter here are on `banglaShare`, not on `detect`.
//
// `detect` returns one of two values, so a test of it can only ever record which side of the line a
// sample fell on — which would be equally true of a threshold of 0.2 and of one of 0.9. The share
// is what shows how far from the line each kind of text actually lands, and that distance is the
// whole argument for the number: **real text does not land near the threshold.** The table the last
// test prints is where the figures quoted in LanguageDetector's comment come from.
class LanguageDetectorTest {

    private final LanguageDetector detector = new LanguageDetector();

    private static final String ENGLISH_PROSE =
            "A skiplist supports search, add and remove in O(log n) expected time.";

    // The realistic case and the one worth being right about: a Bangla computer-science text does
    // not translate its identifiers or its complexity classes.
    private static final String BANGLA_WITH_CODE =
            "বাইনারি সার্চ ট্রিতে অনুসন্ধানের খরচ O(log n), তবে সবচেয়ে খারাপ ক্ষেত্রে তা O(n) হয়ে "
            + "যায়। push এবং pop দুটি কাজই ধ্রুবক সময়ে হয়।";

    private static final String BANGLA_PROSE =
            "মার্জ সর্ট তালিকাটিকে বারবার দুই ভাগ করে এবং সাজানো অংশগুলো মিলিয়ে দেয়।";

    private static final String ENGLISH_QUOTING_BANGLA =
            "The Bengali word for a data structure is তথ্য কাঠামো, which students encounter in "
            + "their first year of the computer science curriculum here.";

    @Test
    void englishProseHasNoBengaliInItAtAll() {
        assertThat(detector.banglaShare(ENGLISH_PROSE)).isZero();
        assertThat(detector.detect(ENGLISH_PROSE)).isEqualTo(Language.ENGLISH);
    }

    @Test
    void banglaProseWithEnglishTechnicalTermsIsStillBangla() {
        // If the threshold were set where a naive reading would put it — "mostly Bengali", 0.5 or
        // above — this passage would still pass, and one with a code listing on the page would not.
        assertThat(detector.banglaShare(BANGLA_WITH_CODE)).isGreaterThan(0.6);
        assertThat(detector.detect(BANGLA_WITH_CODE)).isEqualTo(Language.BANGLA);
    }

    @Test
    void englishQuotingABanglaTermIsStillEnglish() {
        assertThat(detector.banglaShare(ENGLISH_QUOTING_BANGLA)).isLessThan(0.2);
        assertThat(detector.detect(ENGLISH_QUOTING_BANGLA)).isEqualTo(Language.ENGLISH);
    }

    @Test
    void textWithNoLettersInItIsNotEvidenceOfAnything() {
        // A page of pure arithmetic, an extraction that produced only page furniture, an empty
        // document. Dividing by zero letters would be a crash; guessing Bangla from punctuation
        // would be worse than the default.
        assertThat(detector.detect(null)).isEqualTo(Language.ENGLISH);
        assertThat(detector.detect("")).isEqualTo(Language.ENGLISH);
        assertThat(detector.detect("   \n\t  ")).isEqualTo(Language.ENGLISH);
        assertThat(detector.detect("0.318 4.271 9.006 (2 + 2) = 4")).isEqualTo(Language.ENGLISH);
    }

    @Test
    void bengaliDigitsAndVowelSignsDoNotCountAsBengaliLetters() {
        // Both halves of the ratio are letters, which is what stops the number from meaning
        // different things in different sentences. Bengali digits are not letters, so a Bangla
        // table of numbers is not more Bangla than the prose around it; Bengali vowel signs are
        // combining marks rather than letters, so a word carrying four of them does not count four
        // times. Without the second rule every Bangla sample would score higher against a threshold
        // that was read off samples measured with it.
        assertThat(detector.banglaShare("১২৩৪৫৬৭৮৯০")).isZero();
        assertThat(detector.banglaShare("cost ১২৩ units")).isZero();
    }

    @Test
    void nothingBeyondTheScannedPrefixCanChangeTheAnswer() {
        // The cap is a cost bound with a consequence, so the consequence is written down rather
        // than discovered: a book whose first sixty pages are English is an English book, whatever
        // is appended to it. The second assertion is the other half — the cap is a prefix, not a
        // sample, so a Bangla book longer than it is still Bangla.
        String longEnglish = "the value of the counter increases by one on every step. ".repeat(6000);
        String longBangla = "একটি অ্যারে হলো একই ধরনের উপাদানের ধারাবাহিক সংগ্রহ। ".repeat(6000);

        assertThat(longEnglish.length()).isGreaterThan(250_000);
        assertThat(longBangla.length()).isGreaterThan(250_000);
        assertThat(detector.detect(longEnglish + longBangla)).isEqualTo(Language.ENGLISH);
        assertThat(detector.detect(longBangla + longEnglish)).isEqualTo(Language.BANGLA);
    }

    @Test
    void theTwoKindsOfTextAreNowhereNearTheThreshold() {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("english prose", ENGLISH_PROSE);
        samples.put("english + bangla term", ENGLISH_QUOTING_BANGLA);
        samples.put("bangla + code", BANGLA_WITH_CODE);
        samples.put("bangla prose", BANGLA_PROSE);

        StringBuilder table = new StringBuilder("\n===== script share by sample (19.1) =====\n");
        double mostBengaliEnglish = 0;
        double leastBengaliBangla = 1;
        for (Map.Entry<String, String> sample : samples.entrySet()) {
            double share = detector.banglaShare(sample.getValue());
            table.append(String.format(Locale.ROOT, "%-24s %6.3f  %s%n",
                    sample.getKey(), share, detector.detect(sample.getValue())));
            if (sample.getKey().startsWith("english")) {
                mostBengaliEnglish = Math.max(mostBengaliEnglish, share);
            } else {
                leastBengaliBangla = Math.min(leastBengaliBangla, share);
            }
        }
        System.out.println(table);

        // The threshold's justification as one assertion: nothing lands between the most
        // Bengali-looking English sample and the least Bengali-looking Bangla one, and the gap is
        // wide enough that moving 0.2 anywhere inside it would change no answer here.
        assertThat(leastBengaliBangla - mostBengaliEnglish)
                .as("the two populations should be separated by an empty region, not a boundary")
                .isGreaterThan(0.4);
    }
}
