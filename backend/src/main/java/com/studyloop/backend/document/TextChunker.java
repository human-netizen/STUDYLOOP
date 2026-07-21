package com.studyloop.backend.document;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// Splits a document's page texts into overlapping, retrieval-sized chunks. Chunks are the
// unit RAG embeds, retrieves, and cites, so they must be small enough to be specific yet
// overlap so an idea split across a boundary still lands whole in at least one chunk.
//
// We slide a fixed word window across the whole document (words tagged with their source
// page), stepping by the window minus an overlap. Word count is a deliberate proxy for
// tokens — ~400 words ≈ ~500 tokens for English prose — which keeps this dependency-free;
// a real tokenizer can replace the estimate later without changing the chunk boundaries.
@Component
public class TextChunker {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // ~500-token target with 15% overlap, expressed in words (see class note).
    static final int TARGET_WORDS = 400;
    static final int OVERLAP_WORDS = 60;

    public List<TextChunk> chunk(List<PageText> pages) {
        List<Word> words = flatten(pages);
        if (words.isEmpty()) {
            throw new DocumentExtractionException(
                    "No extractable text was found. If this is a scanned PDF, it needs OCR first.");
        }

        int step = TARGET_WORDS - OVERLAP_WORDS;
        List<TextChunk> chunks = new ArrayList<>();
        int index = 0;
        for (int start = 0; start < words.size(); start += step) {
            int end = Math.min(start + TARGET_WORDS, words.size());
            String content = join(words, start, end);
            chunks.add(new TextChunk(index++, words.get(start).page(), content, estimateTokens(content)));
            if (end == words.size()) {
                break;
            }
        }
        return chunks;
    }

    // Flattens pages into a single word stream, each word remembering its source page.
    private static List<Word> flatten(List<PageText> pages) {
        List<Word> words = new ArrayList<>();
        for (PageText page : pages) {
            for (String token : WHITESPACE.split(page.text().strip())) {
                if (!token.isEmpty()) {
                    words.add(new Word(token, page.pageNumber()));
                }
            }
        }
        return words;
    }

    private static String join(List<Word> words, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append(' ');
            }
            sb.append(words.get(i).text());
        }
        return sb.toString();
    }

    // Rough token estimate: English text averages ~4 characters per token.
    private static int estimateTokens(String content) {
        return (int) Math.ceil(content.length() / 4.0);
    }

    private record Word(String text, int page) { }
}
