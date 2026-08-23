package com.studyloop.backend.chat;

import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.document.LanguageDetector;
import com.studyloop.backend.retrieval.RetrievalResult;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import com.studyloop.backend.retrieval.SectionExpander;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// One question, answered from a course's corpus or not at all — with no conversation, no cache,
// no history and nobody logged in.
//
// This is ChatService's pipeline with everything student-shaped removed, and it exists because
// Phase 20.1 answers a question nobody is currently asking: the corpus watch replies to an open
// forum thread when an upload makes it answerable, hours after the student who asked has gone.
// The parts that had to be shared are the parts that decide *whether an answer is honest* —
// retrieval, the confidence gate, and the grounded prompt — so they are shared, and the parts
// that belong to a live turn are simply absent.
//
// **It reads only course-visible documents.** The reply lands in a thread the whole course can
// read, so the scope it is grounded on has to be the whole course's too; see
// RetrievalService.searchAsCourse for how that is expressed. Grounding a public answer on a
// private note would be the Phase 16.3 leak again, arriving through a door that did not exist
// when that clause was written.
@Service
@RequiredArgsConstructor
public class CorpusAnswerService {

    // The same k as a chat turn. A different number here would mean the forum's answers were
    // generated from a different amount of context than the chat answer to the same question,
    // which is a difference nobody could see and everybody would have to explain.
    private static final int RETRIEVAL_K = 6;

    private final RetrievalService retrievalService;
    private final SectionExpander sectionExpander;
    private final ConfidenceGate confidenceGate;
    private final ChatClient chatClient;
    private final LanguageDetector languageDetector;

    // Empty when the corpus cannot answer this — the gate's decision, unchanged and unappealed.
    // That is the common case on any given upload, and it must stay cheap: no provider call is
    // made unless retrieval got past the gate.
    //
    // Not transactional itself beyond the read: the provider call is the slow part and there is
    // nothing to write here. The caller decides what to do with the answer.
    @Transactional(readOnly = true)
    public Optional<GroundedAnswer> answer(UUID courseId, String question) {
        if (!chatClient.isConfigured()) {
            return Optional.empty();
        }
        String trimmed = question == null ? "" : question.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        RetrievalResult retrieval = retrievalService.searchAsCourse(courseId, trimmed, RETRIEVAL_K);
        if (confidenceGate.shouldRefuse(retrieval)) {
            return Optional.empty();
        }

        List<RetrievedChunk> chunks = retrieval.chunks();
        Language language = languageDetector.detect(trimmed);
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(GroundedPrompt.system(
                chunks, sectionExpander.expand(chunks), language, 0)));
        messages.add(LlmMessage.user(trimmed));

        String text = chatClient.complete(messages);
        // The cosine rather than the rerank relevance the gate may actually have read, because
        // the only consumer of this number is a comparison against the refusal that came before
        // it — and question_events records the cosine. Two readings of one instrument, or none.
        Double topSimilarity = retrieval.topVectorSimilarity().isPresent()
                ? retrieval.topVectorSimilarity().getAsDouble()
                : null;
        return Optional.of(new GroundedAnswer(text, citations(chunks), topSimilarity));
    }

    private static List<Citation> citations(List<RetrievedChunk> chunks) {
        List<Citation> citations = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            citations.add(Citation.from(i + 1, chunks.get(i)));
        }
        return citations;
    }

    // An answer and what it was grounded on. `citations` are numbered from one and match the [n]
    // markers in the text, exactly as a chat answer's do — a caller that renders the text without
    // rendering these has published markers that point at nothing.
    public record GroundedAnswer(String text, List<Citation> citations, Double topSimilarity) { }
}
