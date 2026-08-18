package com.studyloop.backend.document;

import com.studyloop.backend.config.ChunkingProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 13.3 — tier 2, for documents that state no structure at all.
//
// The embedding client is a stub with one property that matters: sentences containing the same
// keyword embed to the same direction, and sentences about a different keyword embed orthogonally.
// That is the only thing tier 2 reads, so it is the only thing worth faking; a real provider would
// make these tests slow, paid and non-deterministic without testing anything more.
class SemanticSplitterTest {

    private static final ChunkingProperties PROPERTIES = ChunkingProperties.defaults();

    // Topic A and topic B, six sentences each. A subject change with nothing marking it — no
    // heading, no blank line, no numbering. This is the whole case for tier 2 existing.
    private static final List<String> TWO_TOPICS = List.of(
            "Skiplists use random coin tosses to decide the height of each element.",
            "Skiplists support search in expected logarithmic time.",
            "Skiplists are built from a sequence of singly linked lists.",
            "Skiplists avoid the rebalancing that a search tree requires.",
            "Skiplists store each element once with an array of forward pointers.",
            "Skiplists degrade gracefully when the coin tosses are unlucky.",
            "Hashing maps a key onto a bucket with a multiplicative constant.",
            "Hashing resolves collisions by chaining or by open addressing.",
            "Hashing gives expected constant time when the load factor is bounded.",
            "Hashing requires a table that is resized as elements accumulate.",
            "Hashing with tabulation gives stronger independence guarantees.",
            "Hashing performance is stated in terms of the expected chain length.");

    private static SectionBlock blockOf(List<String> sentences) {
        List<TextUnit> units = new ArrayList<>();
        for (String sentence : sentences) {
            units.add(new TextUnit(sentence, 1));
        }
        return SectionBlock.of(List.of(), units);
    }

    // A vector per topic: same first word → same direction, different first word → orthogonal. Each
    // distinct first word claims the next free dimension, so two topics can never collide onto one
    // — which a hash of the word could, silently, and only on some JDKs.
    private static EmbeddingClient topicEmbedder(AtomicInteger calls) {
        return new EmbeddingClient() {
            private final List<String> topics = new ArrayList<>();

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                calls.incrementAndGet();
                List<float[]> vectors = new ArrayList<>(texts.size());
                for (String text : texts) {
                    String topic = text.split(" ")[0];
                    if (!topics.contains(topic)) {
                        topics.add(topic);
                    }
                    float[] vector = new float[16];
                    vector[topics.indexOf(topic) % vector.length] = 1f;
                    vectors.add(vector);
                }
                return vectors;
            }
        };
    }

    private SemanticSplitter splitter(EmbeddingClient client, ChunkingProperties properties) {
        return new SemanticSplitter(client, new TokenCounter(), properties);
    }

    @Test
    void theSubjectChangeBecomesTheBoundary() {
        AtomicInteger calls = new AtomicInteger();
        List<SectionBlock> blocks =
                splitter(topicEmbedder(calls), PROPERTIES).split(blockOf(TWO_TOPICS));

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).text()).contains("Skiplists").doesNotContain("Hashing");
        assertThat(blocks.get(1).text()).contains("Hashing").doesNotContain("Skiplists");
        // One embedding call for the whole document, not one per sentence.
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aProviderOutageFallsBackToOneBlockRatherThanFailingTheUpload() {
        EmbeddingClient failing = new EmbeddingClient() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                throw new EmbeddingException("provider is down");
            }
        };

        // Chunking degrades to a worse boundary; it does not turn a readable PDF into a failed one.
        assertThat(splitter(failing, PROPERTIES).split(blockOf(TWO_TOPICS))).hasSize(1);
    }

    @Test
    void anUnconfiguredProviderIsNotAnError() {
        assertThat(splitter(Chunkers.unconfigured(), PROPERTIES).split(blockOf(TWO_TOPICS)))
                .hasSize(1);
    }

    @Test
    void theStageCanBeTurnedOff() {
        AtomicInteger calls = new AtomicInteger();
        ChunkingProperties off = new ChunkingProperties(500, 120, false, 400, true, true, 1200);

        assertThat(splitter(topicEmbedder(calls), off).split(blockOf(TWO_TOPICS))).hasSize(1);
        assertThat(calls.get()).as("nothing should have been embedded").isZero();
    }

    @Test
    void aDocumentTooLargeToBeWorthAPassIsLeftToTheParagraphTier() {
        AtomicInteger calls = new AtomicInteger();
        ChunkingProperties tightCap = new ChunkingProperties(500, 120, true, 4, true, true, 1200);

        // The cap is a bill, not a correctness rule: embedding every sentence of a 300-page scan
        // costs a pass over the whole document on top of the pass that embeds the chunks.
        assertThat(splitter(topicEmbedder(calls), tightCap).split(blockOf(TWO_TOPICS))).hasSize(1);
        assertThat(calls.get()).isZero();
    }

    @Test
    void tooFewSentencesToMeasureAreLeftAlone() {
        AtomicInteger calls = new AtomicInteger();
        List<SectionBlock> blocks = splitter(topicEmbedder(calls), PROPERTIES)
                .split(blockOf(TWO_TOPICS.subList(0, 3)));

        // A standard deviation over two samples is not a measurement.
        assertThat(blocks).hasSize(1);
        assertThat(calls.get()).isZero();
    }

    @Test
    void everySentenceKeepsItsPage() {
        List<TextUnit> units = List.of(
                new TextUnit(String.join(" ", TWO_TOPICS.subList(0, 6)), 7),
                new TextUnit(String.join(" ", TWO_TOPICS.subList(6, 12)), 8));
        List<SectionBlock> blocks = splitter(topicEmbedder(new AtomicInteger()), PROPERTIES)
                .split(SectionBlock.of(List.of(), units));

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).pageStart()).isEqualTo(7);
        assertThat(blocks.get(1).pageStart()).isEqualTo(8);
    }
}
