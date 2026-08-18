package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Tier 3: the only tier that exists because of a size limit rather than because of the text.
//
// It runs on nothing but blocks that came out of tier 1 or tier 2 above the ceiling — a chapter
// written as one long unbroken section, a slide with no structure at all. Its rule is that a
// boundary is still a boundary the author put there: paragraphs first, and sentences only when a
// single paragraph is itself too big. It never cuts mid-sentence, which is the specific failure the
// old sliding window produced on every chunk and which the 60-word overlap existed to paper over.
@Component
@RequiredArgsConstructor
public class RecursiveSplitter {

    private final TokenCounter tokenCounter;

    // Packs the block's units into as few sub-blocks as fit under the ceiling, in order. Greedy on
    // purpose: an optimal packing would reorder or rebalance, and the order is the document's.
    public List<SectionBlock> split(SectionBlock block, int maxTokens) {
        List<TextUnit> units = block.units();
        if (tokenCounter.count(block.text()) <= maxTokens) {
            return List.of(block);
        }

        List<SectionBlock> result = new ArrayList<>();
        List<TextUnit> current = new ArrayList<>();
        int currentTokens = 0;

        for (TextUnit unit : atMostOneCeilingEach(units, maxTokens)) {
            int tokens = tokenCounter.count(unit.text());
            if (!current.isEmpty() && currentTokens + tokens > maxTokens) {
                result.add(block.withUnits(current));
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(unit);
            currentTokens += tokens;
        }
        if (!current.isEmpty()) {
            result.add(block.withUnits(current));
        }
        return result;
    }

    // Steps down a rung: any unit still over the ceiling on its own becomes its sentences. Applied
    // before packing rather than during it, so the packer only ever sees units it can actually fit.
    private List<TextUnit> atMostOneCeilingEach(List<TextUnit> units, int maxTokens) {
        List<TextUnit> refined = new ArrayList<>(units.size());
        for (TextUnit unit : units) {
            if (tokenCounter.count(unit.text()) <= maxTokens) {
                refined.add(unit);
                continue;
            }
            List<TextUnit> sentences = Sentences.of(List.of(unit));
            // One "sentence" longer than the whole ceiling is not prose — it is a table flattened
            // into a line, or an equation with no punctuation in it. There is no author boundary
            // left to respect, so this is the one place a word boundary decides, and it is bounded:
            // it only ever happens inside a single oversized unit.
            for (TextUnit sentence : sentences) {
                if (tokenCounter.count(sentence.text()) <= maxTokens) {
                    refined.add(sentence);
                } else {
                    refined.addAll(onWords(sentence, maxTokens));
                }
            }
        }
        return refined;
    }

    private List<TextUnit> onWords(TextUnit unit, int maxTokens) {
        String[] words = unit.text().split("\\s+");
        List<TextUnit> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() > 0 && tokenCounter.count(current + " " + word) > maxTokens) {
                pieces.add(new TextUnit(current.toString(), unit.page()));
                current = new StringBuilder();
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            pieces.add(new TextUnit(current.toString(), unit.page()));
        }
        return pieces;
    }
}
