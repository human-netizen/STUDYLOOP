package com.studyloop.backend.guide;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.ConfidenceGate;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.config.StudyGuideProperties;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.retrieval.RetrievalResult;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import com.studyloop.backend.retrieval.SectionExpander;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Phase 22.1 — turning a topic into an outline, and each heading of that outline into a section
// that is either grounded or honestly empty.
//
// **Grounding is mandatory here, not a flag, and that is the inversion this phase is built on.**
// ZenLearn generates a document from one `context` string and ships `validate_content` — its
// grounding check — off by default. The result reads like a study guide and is a model essay with
// a bibliography stapled to it. Everything in this class is the other choice: retrieval happens
// per section, the confidence gate runs per section, and a section the gate refuses is *listed as
// a gap* rather than written from the model's own knowledge.
//
// **A gap is an output, not an error.** "The course materials don't cover X" is useful to a
// student — it is the sentence that sends them to the forum, or to the instructor, or to the
// library — and it is a sentence an ungrounded generator physically cannot produce, because it has
// no way to know it was about to invent something.
//
// **The retrieval query is the heading, and that decides how the outline prompt is written.** The
// alternative — searching for `topic + heading` every time — hands each section the topic's own
// strongest passages, so every section retrieves roughly the same thing and the citations blur
// back into one context, which is the exact failure per-section retrieval exists to prevent. So
// the query stays specific and the prompt carries the cost: each heading must name its own
// subject. A guide whose headings are "Introduction", "Details" and "Conclusion" would retrieve
// noise three times, and the fix for that is a prompt that does not ask for those headings.
@Service
@RequiredArgsConstructor
public class StudyGuidePlanner {

    private static final Logger log = LoggerFactory.getLogger(StudyGuidePlanner.class);

    // One retry when a reply will not parse, on VideoPlanner's reasoning: json_object is a strong
    // hint rather than a guarantee, there is nothing in the request to fix, and the second call is
    // cheaper than losing the work already spent. Two attempts and not three — a reply that fails
    // twice is a prompt problem, and a third try is a slower way to learn that.
    private static final int PARSE_ATTEMPTS = 2;
    private static final int LOGGED_REPLY_CHARS = 2000;

    // A heading is a search query and a line of a contents page; anything longer is a sentence.
    private static final int MAX_HEADING_LENGTH = 120;
    // Mermaid source past this is not a diagram anybody reads, and it is the one field here that
    // reaches a client-side renderer rather than a Markdown parser.
    private static final int MAX_DIAGRAM_LENGTH = 2000;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    private final RetrievalService retrievalService;
    private final SectionExpander sectionExpander;
    private final ConfidenceGate confidenceGate;
    private final ChatClient chatClient;
    private final StudyGuideProperties properties;

    // ── the outline ─────────────────────────────────────────────────────────────────────────

    // Empty when the corpus cannot support the topic at all — the gate's decision, unappealed, and
    // the same decision chat would have made about the same question.
    //
    // **The refusal happens here, before the outline call, and the ordering is the point.** The
    // most expensive user action in this product must have the cheapest possible failure: a topic
    // the course has nothing on ends at one embedding, rather than after six model calls have
    // produced a confident guide to material that does not exist. VideoPlanner put the same gate
    // in front of a renderer for the same reason.
    //
    // **Not `@Transactional(readOnly = true)`, unlike VideoPlanner.plan, and the difference is
    // Phase 26.2's finding rather than an inconsistency.** The annotation there holds a pooled
    // Supabase connection across the provider calls inside it; the pool is five. Everything this
    // method reads is a single statement — retrieval's candidates are one `union all` since 26.2,
    // and section expansion is one `in` — and a single statement needs no transaction to be
    // consistent. A guide is the worst case for getting this wrong: six sections would pin one of
    // five connections for the length of six completions.
    public Optional<Outline> outline(UUID actorId, UUID courseId, String topic) {
        RetrievalResult retrieval = retrievalService.search(
                actorId, courseId, topic, properties.retrievalK());
        if (confidenceGate.shouldRefuse(retrieval)) {
            return Optional.empty();
        }

        List<RetrievedChunk> chunks = retrieval.chunks();
        // The language of the material, not of the request — Phase 19.3's rule, and 21.2 applies
        // it identically. A student typing an English topic against a Bangla textbook should get
        // the textbook's language, because that is the vocabulary its citations are in.
        Language language = dominantLanguage(chunks);

        OutlineDraft draft = callJson(
                List.of(LlmMessage.system(outlinePrompt(language)),
                        LlmMessage.user("Topic: " + topic + "\n\nCourse material:\n\n"
                                + material(chunks))),
                AiOperation.STUDY_GUIDE_OUTLINE, OutlineDraft.class, "outline");

        List<String> headings = usableHeadings(draft);
        if (headings.isEmpty()) {
            throw new StudyGuideException("The model returned an outline with no sections.");
        }
        return Optional.of(new Outline(language, headings));
    }

    // Distinct, non-blank, capped. Repeats are dropped case-insensitively because two identically
    // named sections would retrieve identically and be written twice, which reads as a bug in the
    // guide rather than as a model that repeated itself.
    private List<String> usableHeadings(OutlineDraft draft) {
        if (draft == null || draft.sections() == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> headings = new ArrayList<>();
        for (String candidate : draft.sections()) {
            if (headings.size() >= properties.maxSections()) {
                break;
            }
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String heading = truncate(candidate.strip(), MAX_HEADING_LENGTH);
            if (seen.add(heading.toLowerCase(Locale.ROOT))) {
                headings.add(heading);
            }
        }
        return headings;
    }

    // ── one section ─────────────────────────────────────────────────────────────────────────

    // Empty means a gap: retrieval for this heading did not get past the gate, so nothing is
    // written under it and no model call is made. **A gap costs one embedding and no completion**,
    // which is why a guide over thin material is cheaper than one over thick material rather than
    // more expensive — the opposite of what an ungrounded generator does, and a property worth
    // being able to state out loud.
    public Optional<WrittenSection> write(UUID actorId, UUID courseId, String heading, Language language) {
        RetrievalResult retrieval = retrievalService.search(
                actorId, courseId, heading, properties.retrievalK());
        if (confidenceGate.shouldRefuse(retrieval)) {
            return Optional.empty();
        }

        List<RetrievedChunk> chunks = retrieval.chunks();
        SectionDraft draft = callJson(
                List.of(LlmMessage.system(sectionPrompt(language)),
                        LlmMessage.user("Section heading: " + heading + "\n\n"
                                + sourcesBlock(chunks))),
                AiOperation.STUDY_GUIDE_SECTION, SectionDraft.class, "section");

        if (draft == null || draft.body() == null || draft.body().isBlank()) {
            throw new StudyGuideException("The model returned a section with no text.");
        }
        return Optional.of(new WrittenSection(
                draft.body().strip(), diagram(draft), citations(chunks)));
    }

    // The Mermaid source, or null — and null for anything that is not plainly a diagram.
    //
    // **This is the one string in the phase that reaches a renderer rather than a Markdown
    // parser**, so it is filtered here as well as sandboxed there. Mermaid's `click` directive
    // binds a callback or a URL to a node, which is a script primitive in a diagram written by a
    // model out of text a student typed — the same hazard `rehype-raw` was refused for in 11.2,
    // arriving through a different door. The client sets `securityLevel: 'strict'` too; a diagram
    // that has to pass both is a diagram, and one that fails either is dropped rather than fixed.
    private String diagram(SectionDraft draft) {
        if (!properties.diagrams() || draft.diagram() == null || draft.diagram().isBlank()) {
            return null;
        }
        String source = draft.diagram().strip();
        if (source.length() > MAX_DIAGRAM_LENGTH) {
            return null;
        }
        String lowered = source.toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("click ", "<script", "javascript:", "href")) {
            if (lowered.contains(forbidden)) {
                log.warn("Dropped a generated diagram containing '{}'", forbidden.strip());
                return null;
            }
        }
        return source;
    }

    // Numbered from one within this section, which is what makes per-section retrieval visible in
    // the output: [2] in section 4 is section 4's second source, not the guide's second source.
    private static List<Citation> citations(List<RetrievedChunk> chunks) {
        List<Citation> citations = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            citations.add(Citation.from(i + 1, chunks.get(i)));
        }
        return citations;
    }

    // ── provider plumbing ───────────────────────────────────────────────────────────────────

    private <T> T callJson(List<LlmMessage> messages, AiOperation operation, Class<T> type, String what) {
        JsonProcessingException failure = null;
        for (int attempt = 1; attempt <= PARSE_ATTEMPTS; attempt++) {
            String json;
            // The scope names the call for the cost dashboard; completeJson is shared with quizzes,
            // summaries and video planning and cannot tell them apart on its own.
            try (var ignored = AiUsageContext.of(operation)) {
                json = chatClient.completeJson(messages);
            } catch (RuntimeException e) {
                throw new StudyGuideException("The study guide provider failed.", e);
            }
            try {
                return objectMapper.readValue(objectPart(json), type);
            } catch (JsonProcessingException e) {
                failure = e;
                // The reply is logged, not only the parser's complaint: "Unexpected end-of-input"
                // cannot tell a truncated answer from a chatty one, and by the time anyone reads
                // the guide's error the reply itself is gone.
                log.warn("Malformed {} on attempt {} of {}: {}. Reply was: {}",
                        what, attempt, PARSE_ATTEMPTS, e.getOriginalMessage(), abbreviate(json));
            }
        }
        throw new StudyGuideException("The model returned a malformed " + what + ".", failure);
    }

    // The object inside whatever the model wrapped it in — a code fence, a line of preamble. The
    // alternative is discarding a correct answer over its packaging.
    private static String objectPart(String json) {
        String text = json.strip();
        if (text.startsWith("{")) {
            return text;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private static String abbreviate(String json) {
        String text = json == null ? "" : json.strip();
        return text.length() <= LOGGED_REPLY_CHARS
                ? text
                : text.substring(0, LOGGED_REPLY_CHARS) + "… (" + text.length() + " chars in all)";
    }

    // The outline's material: what the course has on the topic, unnumbered. Unnumbered because an
    // outline cites nothing — it is a list of headings — and showing [n] markers to a call whose
    // output must not contain them invites exactly the markers that would then point at sources
    // this guide never stored.
    private String material(List<RetrievedChunk> chunks) {
        List<String> expanded = sectionExpander.expand(chunks);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            text.append("(").append(chunk.filename());
            if (chunk.sectionPath() != null) {
                text.append(" · ").append(chunk.sectionPath());
            }
            text.append(")\n").append(expanded.get(i).strip()).append("\n\n");
        }
        return text.toString();
    }

    // The section's sources, numbered exactly as GroundedPrompt numbers them for a chat answer.
    //
    // Rendered here rather than by calling GroundedPrompt.sourcesBlock: that method is
    // package-private to `chat` and shared by the two paths that must cite identically to each
    // other. This is a third caller with a different contract — it returns JSON, not prose — and
    // widening the visibility of the chat prompt to reach it would make a change to chat's
    // grounding rules silently a change to guides.
    private String sourcesBlock(List<RetrievedChunk> chunks) {
        List<String> expanded = sectionExpander.expand(chunks);
        StringBuilder text = new StringBuilder("Sources:\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            text.append('[').append(i + 1).append("] (").append(chunk.filename());
            if (chunk.pageNumber() != null) {
                text.append(", p.").append(chunk.pageNumber());
            }
            if (chunk.sectionPath() != null) {
                text.append(" · ").append(chunk.sectionPath());
            }
            text.append(")\n").append(expanded.get(i).strip()).append("\n\n");
        }
        return text.toString();
    }

    // Majority script among the retrieved chunks; a tie or an empty list is English. The same rule
    // and the same reason as VideoPlanner: English is what a document is when nothing says
    // otherwise, rather than a peer of the other value.
    private static Language dominantLanguage(List<RetrievedChunk> chunks) {
        int bangla = 0;
        for (RetrievedChunk chunk : chunks) {
            if (chunk.content() != null && hasBengali(chunk.content())) {
                bangla++;
            }
        }
        return bangla * 2 > chunks.size() ? Language.BANGLA : Language.ENGLISH;
    }

    private static boolean hasBengali(String text) {
        int limit = Math.min(text.length(), 400);
        for (int i = 0; i < limit; i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.BENGALI) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    // ── prompts ─────────────────────────────────────────────────────────────────────────────

    private String outlinePrompt(Language language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are StudyLoop's study guide planner. Using ONLY the course material below, \
                plan the sections of a study guide on the student's topic.

                Return a SINGLE JSON object, nothing else, with this exact shape:
                { "sections": ["first section heading", "second section heading"] }

                Rules:
                - Plan at most %d sections, in the order a student should read them.
                - **Each heading is also a search query**, so it must name its own subject and \
                stand on its own. "How B-trees keep their height balanced" is a heading. \
                "Introduction", "Details", "Key points" and "Conclusion" are not — never use them.
                - Plan only sections the course material above suggests. Do not plan a section \
                because the topic usually has one.
                - No numbering, no markdown, no prose outside the JSON.
                """.formatted(properties.maxSections()));
        if (language != Language.ENGLISH) {
            prompt.append("- Write the headings in ").append(language.promptName())
                    .append(", keeping technical terms and identifiers as they appear.\n");
        }
        return prompt.toString();
    }

    // The section prompt, and it is a near-relative of GroundedPrompt rather than a copy: same
    // rule about answering only from the numbered sources, same [n] contract, and one difference
    // that matters — this one is told to *stop* rather than to hedge. A chat answer that says "the
    // sources do not cover this" is a reasonable turn; a study guide section that says it is a gap
    // pretending to be a section, and the gate has already decided which one this is.
    private String sectionPrompt(Language language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are StudyLoop's study guide writer. Write ONE section of a study guide, using \
                ONLY the numbered sources below.

                Return a SINGLE JSON object, nothing else, with this exact shape:
                { "body": "the section, in markdown", "diagram": null }

                Rules for "body":
                - 100 to 250 words. Explain, do not summarise the sources one by one.
                - Cite every claim with its source number in square brackets, e.g. [1] or [2][3].
                - Use ONLY what the sources say. If a source does not support a sentence, do not \
                write that sentence. Never fill a gap from outside knowledge.
                - Markdown for structure — short paragraphs, lists where the material is a list. \
                No heading line: the heading is already on the page above your text.
                """);
        if (properties.diagrams()) {
            prompt.append("""

                    Rules for "diagram":
                    - Mermaid source, or null. Null is the normal answer.
                    - Give one ONLY when the sources describe something with a shape: a process \
                    with ordered steps, a hierarchy, a state machine, a comparison of two \
                    structures. Prose about a definition gets null.
                    - Everything in it must come from the sources. A diagram is a claim like any \
                    other sentence, and an invented one is worse than none because a picture is \
                    read as fact.
                    - Start with the graph type on its own line (`flowchart TD`, `sequenceDiagram`, \
                    `stateDiagram-v2`). At most 12 nodes. No `click` directives, no links, no HTML.
                    """);
        } else {
            prompt.append("- Always return null for \"diagram\".\n");
        }
        if (language != Language.ENGLISH) {
            prompt.append("- Write the section in ").append(language.promptName())
                    .append(", whatever language the sources are written in. Keep technical terms, ")
                    .append("identifiers and the [n] citation markers exactly as they appear.\n");
        }
        return prompt.toString();
    }

    // ── shapes ──────────────────────────────────────────────────────────────────────────────

    // The plan, plus the language every section of it will be written in. The topic's own chunks
    // are deliberately *not* carried forward: each section retrieves for itself, which is the
    // whole of 22.1.
    public record Outline(Language language, List<String> headings) { }

    public record WrittenSection(String body, String diagram, List<Citation> citations) { }

    record OutlineDraft(List<String> sections) { }

    record SectionDraft(String body, String diagram) { }
}
