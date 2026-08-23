package com.studyloop.backend.retrieval.eval;

import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

// Phase 19.4 — a Bangla corpus and ten questions against it, kept apart from the English golden
// set rather than merged into it.
//
// **Reported separately because merging would hide which half of a corpus a change helped.** Ten
// Bangla questions folded into fifty-six English ones move a mean by a sixth of whatever they do,
// which is inside the noise of every stage this project has measured. Apart, they answer their own
// question and answer it sharply.
//
// **What this is and is not, stated plainly, because the difference matters when the numbers are
// read.** The English golden set grades against fourteen chapters of a real textbook that somebody
// else wrote. There is no Bangla textbook in this corpus, so the passages below are written here,
// and the questions are written against them by the same person on the same afternoon. That makes
// this a measurement of a *mechanism* — does an AND-joined lexeme query match Bangla text, does a
// trigram query survive a Bangla case suffix — and not a measurement of how well StudyLoop would
// serve a real Bangla course. Any number produced from it should be read with that sentence
// attached. What keeps it honest is that the mechanism it tests is decided by Postgres and by the
// shape of the language, neither of which the author of the fixture controls.
//
// The passages are ordinary data-structures prose of the kind a Bangla CS course actually uses:
// Bengali sentences carrying English technical terms, identifiers and complexity classes
// untranslated, because that is how the subject is written and taught in Bangladesh.
public final class BanglaGoldenSet {

    private BanglaGoldenSet() {
    }

    // One authored page each, separated in the .docx by a real page break, so a citation's page
    // number means the section it names. A Word document has no pages except the ones its author
    // forced, which DocxExtractor says out loud and this fixture cooperates with.
    public record Section(String heading, String body) {
    }

    // `expectedPage` is the one page that answers the question. One page rather than a span,
    // because these passages are short and self-contained — the ambiguity the English set's
    // multi-page answers carry is not present here, and inventing it would be pretending.
    public record BanglaQuestion(String id, String question, int expectedPage) {
    }

    public static final List<Section> SECTIONS = List.of(
            new Section("অ্যারে",
                    "একটি অ্যারে হলো একই ধরনের উপাদানের ধারাবাহিক সংগ্রহ। সূচক ব্যবহার করে যেকোনো "
                    + "উপাদানে ধ্রুবক সময়ে প্রবেশ করা যায়, অর্থাৎ সেই কাজের খরচ O(1)। তবে তালিকার "
                    + "মাঝখানে নতুন উপাদান যোগ করতে হলে তার পরের সব উপাদান এক ঘর সরাতে হয়, ফলে সেই "
                    + "কাজের খরচ O(n)।"),
            new Section("লিংকড লিস্ট",
                    "লিংকড লিস্টে প্রতিটি নোড নিজের মান এবং পরবর্তী নোডের ঠিকানা ধরে রাখে। তালিকার "
                    + "শুরুতে সংযোজন এবং অপসারণ ধ্রুবক সময়ে সম্পন্ন হয়। কিন্তু সূচক ধরে কোনো উপাদানে "
                    + "পৌঁছাতে হলে শুরু থেকে হেঁটে যেতে হয়, তাই সেই খরচ O(n)।"),
            new Section("স্ট্যাক",
                    "স্ট্যাক একটি শেষে ঢুকে প্রথমে বের হওয়া কাঠামো, যাকে সংক্ষেপে LIFO নীতি বলা হয়। "
                    + "push এবং pop দুটি কাজই ধ্রুবক সময়ে হয়। ফাংশন কল ব্যবস্থাপনা এবং ব্যাকট্র্যাকিং "
                    + "অ্যালগরিদমে স্ট্যাক ব্যবহৃত হয়।"),
            new Section("কিউ",
                    "কিউ একটি প্রথমে ঢুকে প্রথমে বের হওয়া কাঠামো, সংক্ষেপে FIFO। পিছনে সংযোজন এবং "
                    + "সামনে অপসারণ দুটোই ধ্রুবক সময়ে হয়। প্রস্থ-প্রথম অনুসন্ধানে এই কাঠামো ব্যবহার "
                    + "করা হয়।"),
            new Section("বাইনারি সার্চ ট্রি",
                    "বাইনারি সার্চ ট্রিতে প্রতিটি নোডের বাম দিকে ছোট মান এবং ডান দিকে বড় মান থাকে। "
                    + "ভারসাম্যপূর্ণ অবস্থায় অনুসন্ধানের খরচ O(log n)। কিন্তু গাছটি যদি একটি শিকলের "
                    + "মতো লম্বা হয়ে যায়, তবে সবচেয়ে খারাপ ক্ষেত্রে সেই খরচ দাঁড়ায় O(n)।"),
            new Section("হ্যাশ টেবিল",
                    "হ্যাশ টেবিল একটি হ্যাশ ফাংশন ব্যবহার করে চাবিকে ঘরের সূচকে রূপান্তর করে। গড়ে "
                    + "অনুসন্ধান, সংযোজন ও অপসারণ ধ্রুবক সময়ে হয়। দুটি চাবি একই ঘরে পড়লে সংঘর্ষ ঘটে, "
                    + "এবং চেইনিং পদ্ধতিতে প্রতিটি ঘরে একটি তালিকা রেখে সেই সংঘর্ষ সামলানো হয়।"),
            new Section("বাইনারি হিপ",
                    "বাইনারি হিপ একটি সম্পূর্ণ বাইনারি গাছ, যেখানে প্রতিটি অভিভাবক নোডের মান তার "
                    + "সন্তানদের মানের চেয়ে ছোট। সন্নিবেশ এবং সর্বনিম্ন মান অপসারণ দুটোরই খরচ "
                    + "O(log n)। প্রায়োরিটি কিউ সাধারণত হিপ দিয়ে তৈরি করা হয়।"),
            new Section("স্কিপ লিস্ট",
                    "স্কিপ লিস্ট একটি সম্ভাবনা নির্ভর কাঠামো, যেখানে কয়েকটি স্তরে সাজানো লিংকড লিস্ট "
                    + "ব্যবহার করা হয়। প্রতিটি নোড মুদ্রা নিক্ষেপের মতো এলোমেলোভাবে উপরের স্তরে ওঠে। "
                    + "প্রত্যাশিত অনুসন্ধান সময় O(log n)।"),
            new Section("কুইকসর্ট",
                    "কুইকসর্ট একটি পিভট বেছে নিয়ে তালিকাটিকে দুই ভাগে ভাগ করে। গড়ে এর সময় জটিলতা "
                    + "O(n log n)। পিভট বারবার সবচেয়ে ছোট বা সবচেয়ে বড় মান হলে সবচেয়ে খারাপ ক্ষেত্রে "
                    + "খরচ O(n^2) হয়ে যায়।"),
            new Section("মার্জ সর্ট",
                    "মার্জ সর্ট তালিকাটিকে বারবার দুই ভাগ করে এবং সাজানো অংশগুলো মিলিয়ে দেয়। এর সময় "
                    + "জটিলতা সব ক্ষেত্রেই O(n log n)। তবে মেলানোর জন্য অতিরিক্ত জায়গা লাগে, যার "
                    + "পরিমাণ O(n)।"),
            new Section("গ্রাফ অনুসন্ধান",
                    "গ্রাফ একটি নোড এবং প্রান্তের সংগ্রহ। প্রস্থ-প্রথম অনুসন্ধান একটি কিউ ব্যবহার করে "
                    + "স্তরে স্তরে এগোয়, আর গভীরতা-প্রথম অনুসন্ধান একটি স্ট্যাক ব্যবহার করে যতদূর সম্ভব "
                    + "গভীরে যায়। দুটি পদ্ধতিরই খরচ O(V + E)।"),
            new Section("ডাইনামিক প্রোগ্রামিং",
                    "ডাইনামিক প্রোগ্রামিং একটি বড় সমস্যাকে ছোট উপ-সমস্যায় ভাগ করে এবং প্রতিটি "
                    + "উপ-সমস্যার উত্তর একবার হিসাব করে সংরক্ষণ করে রাখে। এই সংরক্ষণকে মেমোআইজেশন বলা "
                    + "হয়। ফলে একই হিসাব বারবার করতে হয় না।"));

    // Ten questions, one per page, written the way a student types rather than the way the passage
    // is worded. **Several of them carry the case suffix the passage does not** — "অ্যারেতে" for a
    // page that says "অ্যারে", "কুইকসর্টের" for one that says "কুইকসর্ট" — and that is deliberate
    // rather than sloppy. Bangla marks case by attaching a suffix to the noun, Postgres has no
    // Bangla stemmer, and neither the `english` nor the `simple` text search configuration will
    // reduce the two forms to one lexeme. So a lexeme retriever cannot match them at all, whatever
    // configuration it is given, and that is precisely the claim 19.2 was written to check.
    public static final List<BanglaQuestion> QUESTIONS = List.of(
            new BanglaQuestion("B01", "অ্যারেতে সূচক দিয়ে একটি উপাদানে প্রবেশ করতে কত সময় লাগে?", 1),
            new BanglaQuestion("B02", "লিংকড লিস্টের শুরুতে সংযোজন করতে কত খরচ হয়?", 2),
            new BanglaQuestion("B03", "স্ট্যাক কোন নীতিতে কাজ করে?", 3),
            new BanglaQuestion("B04", "প্রস্থ-প্রথম অনুসন্ধানে কোন কাঠামো ব্যবহার করা হয়?", 4),
            new BanglaQuestion("B05", "বাইনারি সার্চ ট্রিতে অনুসন্ধানের সবচেয়ে খারাপ সময় জটিলতা কত?", 5),
            new BanglaQuestion("B06", "হ্যাশ টেবিলে সংঘর্ষ কীভাবে সামলানো হয়?", 6),
            new BanglaQuestion("B07", "বাইনারি হিপে সন্নিবেশের খরচ কত?", 7),
            new BanglaQuestion("B08", "স্কিপ লিস্টে প্রত্যাশিত অনুসন্ধান সময় কত?", 8),
            new BanglaQuestion("B09", "কুইকসর্টের সবচেয়ে খারাপ ক্ষেত্রে সময় জটিলতা কত?", 9),
            new BanglaQuestion("B10", "মার্জ সর্ট কত অতিরিক্ত জায়গা ব্যবহার করে?", 10));

    // A real .docx rather than a PDF, and the reason is a font.
    //
    // TestPdfs builds its fixtures with PDFBox's standard fourteen fonts, none of which can encode
    // a single Bengali codepoint — writing this text into a PDF that way throws before it produces
    // a byte. A .docx stores its text as UTF-8 inside the OOXML, so the script is a non-issue, and
    // DOCX is a format this application already ingests through the same pipeline as everything
    // else. What is measured below therefore went through real extraction, real language detection
    // and the real chunking ladder rather than being inserted into the table by hand.
    public static byte[] docx() {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < SECTIONS.size(); i++) {
                if (i > 0) {
                    // Ctrl+Enter, which is the only thing in a .docx that says "new page".
                    XWPFRun breakRun = document.createParagraph().createRun();
                    breakRun.addBreak(BreakType.PAGE);
                }
                Section section = SECTIONS.get(i);
                XWPFParagraph heading = document.createParagraph();
                heading.setStyle("Heading1");
                heading.createRun().setText(section.heading());
                document.createParagraph().createRun().setText(section.body());
            }
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not build the Bangla fixture", e);
        }
    }
}
