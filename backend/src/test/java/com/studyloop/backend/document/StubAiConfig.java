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

    // Deterministic, non-zero 768-dim vectors — enough to exercise storage without a real API.
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
                    float[] vector = new float[DIMENSIONS];
                    for (int i = 0; i < DIMENSIONS; i++) {
                        vector[i] = 0.001f * (((text.length() + i) % 7) + 1);
                    }
                    vectors.add(vector);
                }
                return vectors;
            }
        };
    }

    // A chat client that returns whatever the test set and counts how often it was asked. The
    // count is what turns "generated once and cached" from a claim into an assertion.
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

        public final AtomicInteger calls = new AtomicInteger();
        public volatile String nextJson = DEFAULT_JSON;
        // One-shot: the next call throws, and the one after that succeeds again. Lets a test say
        // "the provider was down during ingestion" and then exercise the recovery path.
        public volatile boolean failNext = false;

        public void reset() {
            calls.set(0);
            nextJson = DEFAULT_JSON;
            failNext = false;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String complete(List<LlmMessage> messages) {
            return completeJson(messages);
        }

        @Override
        public String completeJson(List<LlmMessage> messages) {
            calls.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("stub provider is down");
            }
            return nextJson;
        }

        @Override
        public String streamComplete(List<LlmMessage> messages, Consumer<String> onDelta) {
            String answer = completeJson(messages);
            onDelta.accept(answer);
            return answer;
        }
    }
}
