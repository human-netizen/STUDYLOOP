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

    // Embeds a hypothetical answer — a passage the model invented, not a question (Phase 18.2).
    //
    // It exists because `embedQuery` is the wrong call and `embed` is the wrong call, for two
    // different reasons. A HyDE pseudo-document is compared against real documents, so it has to be
    // embedded the way documents are (`search_document` on Cohere): embedded as a query it would
    // sit in the part of the space questions occupy, which is the one thing this technique is
    // trying to get out of. And `embed` is the ingestion path, which sleeps and retries through a
    // rate limit because nobody is waiting on it — here somebody is, in front of a stream.
    //
    // Defaulted to the plain document embedding, so a provider that draws no distinction between
    // the two input types needs no override and simply gets the ordinary behaviour.
    default float[] embedPseudoDocument(String text) {
        List<float[]> vectors = embed(List.of(text));
        if (vectors.isEmpty()) {
            throw new EmbeddingException("Hypothetical answer produced no embedding.");
        }
        return vectors.get(0);
    }

    // Whether this provider puts images in the same vector space as text (Phase 17.1).
    //
    // Defaulted to false so the two providers that cannot — Google's text-embedding-004 and a
    // local Ollama model — need no edit, and so the visual half of the pipeline gates on a
    // capability rather than on a provider name. A corpus ingested against a text-only embedder
    // still gets its visual chunk *rows*; they simply carry no vector, which is the same graceful
    // nothing an unconfigured provider has produced for text chunks since Phase 4.
    default boolean supportsImages() {
        return false;
    }

    // One vector per image, in the same order, in the same space as embed() and embedQuery().
    //
    // That last clause is the entire point and it is a property of the model rather than of this
    // interface: embed-v4.0 is trained so a typed question and a picture of a page land near each
    // other, which is what lets a query about "the diagram with the three layers" match a page
    // nobody wrote a caption for. A provider that cannot do that says so above rather than
    // returning vectors from a second space that would compare against the first as noise.
    default List<float[]> embedImages(List<byte[]> pngImages) {
        throw new EmbeddingException("This embedding provider does not accept images.");
    }
}
