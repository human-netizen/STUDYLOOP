package com.studyloop.backend.document;

import com.studyloop.backend.config.ChunkingProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Tier 2: when the document states no structure, find the places where the subject changes.
//
// Every sentence is embedded, and the similarity between each pair of neighbours is measured. Text
// that is still on the same point reads as a run of high similarities; the moment the subject moves
// on, one of those numbers drops. Cutting at the drops puts boundaries where the meaning changes —
// which is what agentic chunking is usually reached for, at embedding cost instead of generation
// cost, and reproducibly: the same document embeds to the same numbers and cuts in the same places
// on every ingest, which the `(document, chunk_index)` unique constraint and the eval both depend
// on.
//
// **It only runs when tier 1 found nothing at all.** A slide deck exported flat, continuous prose,
// a page a vision model read with no hierarchy in it. That restriction is what keeps it affordable:
// it costs one embedding pass over the whole document, on top of the pass that embeds the chunks,
// so a document that runs it pays roughly twice. Textbooks and lecture PDFs never reach it.
//
// Failure is not fatal. An embedding outage during ingestion returns the block untouched and tier 3
// takes it on paragraphs — a worse boundary, not a failed upload.
@Component
@RequiredArgsConstructor
public class SemanticSplitter {

    private static final Logger log = LoggerFactory.getLogger(SemanticSplitter.class);

    // Below this there is nothing to detect: two or three sentences are one chunk whatever their
    // similarities say, and a standard deviation over two samples is not a measurement.
    private static final int MIN_SENTENCES = 6;
    // How far below the average similarity a drop has to sit before it counts as a boundary.
    // Measured in standard deviations, so it adapts to the document: uniform prose has a small
    // spread and needs a real drop, a mixed document has a wide one and its ordinary variation does
    // not trip it.
    private static final double CUT_SIGMA = 1.0;

    private final EmbeddingClient embeddingClient;
    private final TokenCounter tokenCounter;
    private final ChunkingProperties properties;

    public List<SectionBlock> split(SectionBlock block) {
        if (!properties.semantic() || !embeddingClient.isConfigured()) {
            return List.of(block);
        }
        List<TextUnit> sentences = Sentences.of(block.units());
        if (sentences.size() < MIN_SENTENCES || sentences.size() > properties.semanticSentenceLimit()) {
            // Too small to measure, or large enough that the pass costs more than the boundaries
            // are worth. Either way tier 3 takes it.
            return List.of(block);
        }

        List<float[]> vectors;
        try {
            vectors = embeddingClient.embed(sentences.stream().map(TextUnit::text).toList());
        } catch (RuntimeException e) {
            log.warn("Semantic chunking fell back to paragraphs: {}", e.getMessage());
            return List.of(block);
        }
        if (vectors.size() != sentences.size()) {
            return List.of(block);
        }

        double[] similarities = adjacentSimilarities(vectors);
        double threshold = mean(similarities) - CUT_SIGMA * standardDeviation(similarities);
        return cut(block, sentences, similarities, threshold);
    }

    // Boundaries go where a similarity dropped below the threshold, plus wherever the ceiling
    // forces one — a run of sentences that never changes subject is still capped, or one uniform
    // page would come back as a single 3,000-token vector.
    private List<SectionBlock> cut(SectionBlock block, List<TextUnit> sentences,
                                   double[] similarities, double threshold) {
        List<SectionBlock> blocks = new ArrayList<>();
        List<TextUnit> current = new ArrayList<>();
        int currentTokens = 0;

        for (int i = 0; i < sentences.size(); i++) {
            int tokens = tokenCounter.count(sentences.get(i).text());
            boolean overCeiling = !current.isEmpty() && currentTokens + tokens > properties.maxTokens();
            boolean subjectChanged = !current.isEmpty() && similarities[i - 1] < threshold;
            if (overCeiling || subjectChanged) {
                blocks.add(block.withUnits(current));
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(sentences.get(i));
            currentTokens += tokens;
        }
        if (!current.isEmpty()) {
            blocks.add(block.withUnits(current));
        }
        return blocks;
    }

    // similarities[i] is how alike sentence i and sentence i+1 are.
    private static double[] adjacentSimilarities(List<float[]> vectors) {
        double[] similarities = new double[vectors.size() - 1];
        for (int i = 0; i < similarities.length; i++) {
            similarities[i] = cosine(vectors.get(i), vectors.get(i + 1));
        }
        return similarities;
    }

    // The provider returns unit vectors, but the norms are divided out anyway: a stub, a different
    // provider or a truncated Matryoshka vector need not be normalised, and an unnormalised dot
    // product is not a similarity at all.
    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        double magnitude = Math.sqrt(leftNorm) * Math.sqrt(rightNorm);
        return magnitude == 0 ? 0 : dot / magnitude;
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    private static double standardDeviation(double[] values) {
        double mean = mean(values);
        double sum = 0;
        for (double value : values) {
            sum += (value - mean) * (value - mean);
        }
        return values.length == 0 ? 0 : Math.sqrt(sum / values.length);
    }
}
