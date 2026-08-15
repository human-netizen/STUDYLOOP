package com.studyloop.backend.document;

import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
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
