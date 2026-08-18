package com.studyloop.backend.document;

import com.studyloop.backend.config.ChunkingProperties;

import java.util.List;

// Builds a TextChunker by hand, for the tests that want the real chunking ladder without a Spring
// context — the fixture-corpus guard, which runs in CI over fourteen PDFs, and the chunker's own
// unit tests. Wiring five collaborators in each of them would be five copies of the same paragraph.
public final class Chunkers {

    private Chunkers() {
    }

    // The chunker as configured in production, with tier 2 unavailable. Tier 2 only fires for a
    // document with no headings at all, so anything built from a real textbook never reaches it —
    // and a unit test that silently made network calls would be a worse problem than an untested
    // tier, which SemanticSplitter's own tests cover with a stub.
    public static TextChunker standard() {
        return standard(ChunkingProperties.defaults(), unconfigured());
    }

    public static TextChunker standard(ChunkingProperties properties, EmbeddingClient embeddingClient) {
        TokenCounter tokenCounter = new TokenCounter();
        return new TextChunker(properties, new StructuralSplitter(),
                new SemanticSplitter(embeddingClient, tokenCounter, properties),
                new RecursiveSplitter(tokenCounter), tokenCounter);
    }

    public static EmbeddingClient unconfigured() {
        return new EmbeddingClient() {
            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                throw new IllegalStateException("no embedding provider in this test");
            }
        };
    }
}
