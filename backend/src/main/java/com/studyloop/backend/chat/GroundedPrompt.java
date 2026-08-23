package com.studyloop.backend.chat;

import com.studyloop.backend.document.Language;
import com.studyloop.backend.retrieval.RetrievedChunk;

import java.util.List;

// The system prompt every grounded answer in this application is generated from: answer only
// from the numbered sources, cite each claim as [n], refuse rather than guess.
//
// It was a private method inside ChatService until Phase 20.1, and it moved because it acquired a
// second caller. The corpus watch answers a forum thread from the same corpus, through the same
// retrieval, behind the same confidence gate — and if it built its own prompt then "what the
// assistant says about this course" would depend on which door the question came in through. One
// string, one place, and a change to the grounding rules reaches both surfaces or neither.
//
// Static and stateless: it holds no configuration, and passing a bean around for string
// concatenation would only make it harder to see that the two callers share one prompt.
final class GroundedPrompt {

    private GroundedPrompt() {
    }

    // `sources` is one passage per chunk, in the same order — the chunk's own text, or the section
    // it belongs to. The citation label still comes from the chunk, so [3] means the page retrieval
    // actually matched even when the model was shown the pages around it.
    //
    // `language` (19.3) adds one line and only when it has something to say. A model answers in the
    // language it is instructed in, and this prompt is English, so a Bangla question was coming
    // back in English however the sources were written — the model was following its instructions.
    //
    // `timesAskedBefore` (20.3) adds one more, and only from the third time this student has asked
    // this. **What it passes is a count, never the earlier answer.** Re-injecting the model's own
    // past output is how a wrong explanation becomes this course's permanent position on a topic;
    // a count says "explain it another way" and carries no claim at all.
    //
    // **The plain English prompt is left byte-identical rather than gaining an "answer in English"
    // line**, which is not tidiness: every answer this application has produced was generated from
    // that exact string, and a prompt that changes for every question changes what every one of
    // them says. The two conditional lines are conditional for the same reason.
    static String system(List<RetrievedChunk> chunks, List<String> sources, Language language,
                         int timesAskedBefore) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are StudyLoop's study assistant. Answer the student's question using ONLY the \
                numbered sources below.
                - Cite every claim with its source number in square brackets, e.g. [1] or [2][3].
                - If the sources do not contain the answer, say you don't have that in the course \
                materials. Do not use outside knowledge or guess.
                - Be concise and precise.
                """);
        if (language != Language.ENGLISH) {
            // Named in English, and placed last so it is the instruction nearest the sources. The
            // sources themselves may be in either language: a Bangla course quoting an English
            // paper should still be answered in Bangla, and saying so explicitly is what stops the
            // model from mirroring whichever language the retrieved passages happen to be in.
            prompt.append("- Write your answer in ").append(language.promptName())
                    .append(", whatever language the sources are written in. Keep technical terms, ")
                    .append("identifiers and the [n] citation markers exactly as they appear.\n");
        }
        if (timesAskedBefore > 0) {
            prompt.append("- This student has asked about this topic ").append(timesAskedBefore)
                    .append(timesAskedBefore == 1 ? " time before" : " times before")
                    .append(". Explain it a different way this time — a worked example, or the ")
                    .append("idea it is usually confused with — rather than restating it.\n");
        }
        prompt.append("""

                Sources:
                """);
        if (chunks.isEmpty()) {
            prompt.append("(no relevant sources were found in the course materials)");
            return prompt.toString();
        }
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            prompt.append('[').append(i + 1).append("] (").append(chunk.filename());
            if (chunk.pageNumber() != null) {
                prompt.append(", p.").append(chunk.pageNumber());
            }
            // Where the passage sits in the document. Cheap for the model and worth having: it is
            // the difference between "expected search time is O(log n)" as a floating claim and as
            // a claim about skiplists.
            if (chunk.sectionPath() != null) {
                prompt.append(" · ").append(chunk.sectionPath());
            }
            prompt.append(")\n").append(sources.get(i).strip()).append("\n\n");
        }
        return prompt.toString();
    }

    // The other prompt this application has, and the only one that is *not* grounded (20.2).
    //
    // It exists because a refusal is a dead end for a question the course was never going to
    // cover — "is this on the exam", "what is a monad", the prerequisite nobody uploaded — and the
    // honest options are to leave the student with nothing or to answer while saying plainly that
    // this did not come from their materials. The whole product argument for the second option is
    // that it is *labelled*, so the label is the prompt's first instruction and its last.
    //
    // No sources, and therefore no [n] markers: a citation marker with nothing behind it is worse
    // than no citation, because the rest of this application has trained the reader to trust one.
    static String generalKnowledge(Language language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are StudyLoop's study assistant, answering from general knowledge because the \
                student's course materials do not cover this question.
                - Answer directly and concisely, at the level of a university student.
                - Do NOT use citation markers such as [1]: there are no sources behind this answer.
                - Say plainly when something is uncertain, contested, or depends on the course's \
                own conventions, and suggest asking the instructor when it does.
                """);
        if (language != Language.ENGLISH) {
            prompt.append("- Write your answer in ").append(language.promptName())
                    .append(". Keep technical terms and identifiers as they are.\n");
        }
        return prompt.toString();
    }
}
