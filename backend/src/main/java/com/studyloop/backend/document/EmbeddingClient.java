package com.studyloop.backend.document;

import java.util.List;

// Turns text into embedding vectors. Abstracted from the provider so the pipeline can gate
// on isConfigured() (skipping embeddings when no key is set) and so tests can swap in a stub
// without calling a real API.
public interface EmbeddingClient {

    // Whether a provider is actually configured (e.g. an API key is present).
    boolean isConfigured();

    // Returns one vector per input text, in the same order. These are the documents we index.
    List<float[]> embed(List<String> texts);

    // Embeds a search query. Some providers (e.g. Cohere) embed queries and documents with
    // different "input types" for better retrieval; the default treats a query like any text.
    default float[] embedQuery(String text) {
        List<float[]> vectors = embed(List.of(text));
        if (vectors.isEmpty()) {
            throw new EmbeddingException("Query produced no embedding.");
        }
        return vectors.get(0);
    }
}
