package com.studyloop.backend.document;

import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.retrieval.RerankClient;
import com.studyloop.backend.retrieval.RerankException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

// Stubs both AI providers for the document tests, so ingestion end to end is deterministic, free,
// and runs in CI with no API keys.
//
// It is a single shared class rather than one stub per test class on purpose. Every distinct test
// configuration gets its own cached ApplicationContext, and every context opens its own Hikari
// pool against Supabase's session pooler — which caps total clients at 15. Three document test
// classes with three private configs would be three pools; importing this one config from all
// three makes them share a single context, and leaves headroom for a dev server running alongside
// the suite.
@TestConfiguration
public class StubAiConfig {

    static final int DIMENSIONS = 768;

    @Bean
    @Primary
    RecordingChatClient recordingChatClient() {
        return new RecordingChatClient();
    }

    @Bean
    @Primary
    StubRerankClient stubRerankClient() {
        return new StubRerankClient();
    }

    @Bean
    @Primary
    StubVisionClient stubVisionClient() {
        return new StubVisionClient();
    }

    // Deterministic 768-dim vectors — enough to exercise storage and search without a real API.
    @Bean
    @Primary
    EmbeddingClient stubEmbeddingClient() {
        return new EmbeddingClient() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                List<float[]> vectors = new ArrayList<>(texts.size());
                for (String text : texts) {
                    vectors.add(vectorFor(text));
                }
                return vectors;
            }
        };
    }

    // Keyed on the text's content, not its shape: the same string always embeds to the same
    // vector, and two different strings land essentially orthogonal to each other.
    //
    // Both halves matter to the semantic cache. Identical text has to come back at similarity 1.0
    // or a repeated question never hits; different text has to come back well under 0.97 or an
    // unrelated question hits and is served somebody else's answer. The earlier stub keyed the
    // vector on text.length() alone, which made any two questions of the same length perfect
    // paraphrases of each other — fine for "did we store a vector", useless for anything that
    // compares them.
    static float[] vectorFor(String text) {
        long state = 0x9E3779B97F4A7C15L ^ seed(text);
        float[] vector = new float[DIMENSIONS];
        double sumSquares = 0.0;
        for (int i = 0; i < DIMENSIONS; i++) {
            // xorshift64: deterministic, uniform enough, and no dependency on JDK internals whose
            // output could change between versions.
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            float value = (float) ((state >>> 11) / (double) (1L << 53) * 2.0 - 1.0);
            vector[i] = value;
            sumSquares += (double) value * value;
        }
        float norm = (float) Math.sqrt(sumSquares);
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] /= norm;
        }
        return vector;
    }

    private static long seed(String text) {
        long hash = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) {
            hash = 31 * hash + text.charAt(i);
        }
        return hash;
    }

    // A vision model that reads whatever the test says is on the page (Phase 16).
    //
    // **Unconfigured by default, which is the important part.** The PDF router asks
    // `isConfigured()` before routing anything, so a stub that were live by default would start
    // sending fixture pages to it under every document test that imports this config — quietly
    // replacing PDFBox's text with `MARKDOWN` on any page the gate happened to dislike, and
    // changing the pipeline under tests written against the extracted text. A test that wants the
    // reader switches it on and switches it back off, exactly as RerankPipelineTest does.
    public static class StubVisionClient implements VisionClient {

        public volatile boolean configured = false;
        public volatile RuntimeException failWith = null;
        // What a photographed note reads as. Two blocks by default, one of them below the 0.6
        // threshold, so the default fixture exercises both sides of the confidence rule.
        public volatile List<TranscribedBlock> blocks = List.of(
                new TranscribedBlock(0, "# Amortized Analysis\n\nThe expensive resize pays for "
                        + "the cheap appends before it.", 0.94, false),
                new TranscribedBlock(1, "T(n) = [illegible] + O(n)", 0.22, false));
        public final AtomicInteger calls = new AtomicInteger();

        public void reset() {
            configured = false;
            failWith = null;
            calls.set(0);
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String readPage(byte[] pngImage, PageDefect hint) {
            calls.incrementAndGet();
            if (failWith != null) {
                throw failWith;
            }
            return "# Read by the stub\n\nWhatever was on the page.";
        }

        @Override
        public List<TranscribedBlock> readHandwriting(byte[] image, String mimeType) {
            calls.incrementAndGet();
            if (failWith != null) {
                throw failWith;
            }
            return blocks;
        }
    }

    // A cross-encoder that scores a passage on how much of the question it actually contains.
    //
    // Off unless a test switches it on, which is not laziness about coverage but the same reasoning
    // as blanking the API key in application-test.yml: the rerank stage ships enabled and every
    // retrieval call goes through it, so a stub that were on by default would silently change the
    // pipeline under ten test classes written against the fused one — including the refusal tests,
    // where the gate would start reading a different signal than the one they were built to prove.
    // RerankPipelineTest turns it on deliberately.
    //
    // Word overlap is a crude stand-in for a cross-encoder and a faithful one in the only respect
    // that matters here: it reads the query and the passage together, so an off-topic question
    // scores near zero however confidently the vector half retrieved something.
    public static class StubRerankClient implements RerankClient {

        public volatile boolean configured = false;
        // Every call throws while this is set, standing in for a provider outage or a rate limit.
        // Not one-shot like the chat client's failNext: the interesting question is what a whole
        // turn does with no reranker at all, not what it does with one flaky call.
        public volatile boolean failing = false;
        // Scores every passage at this value instead of by overlap. It exists because overlap
        // cannot express the judgement the gate is most interesting about — "these are the words
        // you asked for, and this passage still does not answer you" — which is precisely what a
        // cross-encoder can say and a term index cannot. A test asserting the gate's reaction to
        // that verdict should state the verdict rather than build a fixture that fakes one.
        public volatile Double forcedRelevance = null;
        public final AtomicInteger calls = new AtomicInteger();

        public void reset() {
            configured = false;
            failing = false;
            forcedRelevance = null;
            calls.set(0);
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public List<Ranked> rerank(String query, List<String> documents, int topN) {
            calls.incrementAndGet();
            if (failing) {
                throw new RerankException("stub reranker is down");
            }
            List<Ranked> ranked = new ArrayList<>(documents.size());
            for (int index = 0; index < documents.size(); index++) {
                ranked.add(new Ranked(index, forcedRelevance != null
                        ? forcedRelevance
                        : overlap(query, documents.get(index))));
            }
            // Best first, and ties broken by the fused position so the ordering is deterministic.
            return ranked.stream()
                    .sorted(Comparator.comparingDouble(Ranked::relevance).reversed()
                            .thenComparingInt(Ranked::index))
                    .limit(topN)
                    .toList();
        }

        private static double overlap(String query, String document) {
            List<String> terms = words(query);
            if (terms.isEmpty()) {
                return 0.0;
            }
            Set<String> passage = new HashSet<>(words(document));
            long matched = terms.stream().distinct().filter(passage::contains).count();
            return (double) matched / terms.stream().distinct().count();
        }

        private static List<String> words(String text) {
            return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                    .filter(word -> word.length() >= 3)
                    .toList();
        }
    }

    // A chat client that returns whatever the test set and counts how often it was asked. The
    // count is what turns "generated once and cached" from a claim into an assertion — for
    // document summaries in 8.2, and for the semantic answer cache in 8.3.
    public static class RecordingChatClient implements ChatClient {

        // The summary + glossary most tests expect back.
        public static final String SUMMARY_TEXT =
                "This document introduces dynamic programming and when it applies.";

        public static final String DEFAULT_JSON = """
                { "summary": "%s",
                  "terms": [
                    { "term": "Dynamic programming",  "definition": "Combining subproblem solutions." },
                    { "term": "Optimal substructure", "definition": "Optimal solutions contain optimal subsolutions." }
                  ] }
                """.formatted(SUMMARY_TEXT);

        // Prose, for the chat paths. Structured features get nextJson instead — one client, two
        // shapes, because that is exactly how the real one behaves.
        public static final String DEFAULT_ANSWER =
                "Dynamic programming combines subproblem solutions [1].";

        public final AtomicInteger calls = new AtomicInteger();
        public volatile String nextJson = DEFAULT_JSON;
        public volatile String nextAnswer = DEFAULT_ANSWER;
        // One-shot: the next call throws, and the one after that succeeds again. Lets a test say
        // "the provider was down during ingestion" and then exercise the recovery path.
        public volatile boolean failNext = false;

        public void reset() {
            calls.set(0);
            nextJson = DEFAULT_JSON;
            nextAnswer = DEFAULT_ANSWER;
            failNext = false;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(List<LlmMessage> messages) {
            countCall();
            return nextAnswer;
        }

        @Override
        public String completeJson(List<LlmMessage> messages) {
            countCall();
            return nextJson;
        }

        @Override
        public String streamComplete(List<LlmMessage> messages, Consumer<String> onDelta) {
            countCall();
            onDelta.accept(nextAnswer);
            return nextAnswer;
        }

        // Every provider entry point runs through here, so `calls` counts calls that reached the
        // provider regardless of which shape was asked for.
        private void countCall() {
            calls.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("stub provider is down");
            }
        }
    }
}
