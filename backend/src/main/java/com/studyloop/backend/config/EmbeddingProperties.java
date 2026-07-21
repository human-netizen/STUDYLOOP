package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for turning chunks into vectors. `provider` selects which backend embeds text:
// "cohere" (default) and "google" are hosted APIs (need a key); "ollama" runs a local, offline
// model. When the selected provider has no key configured, ingestion stores chunks without
// embeddings rather than failing.
@ConfigurationProperties(prefix = "studyloop.embedding")
public record EmbeddingProperties(String provider, Cohere cohere, Google google, Ollama ollama) {

    // apiKey from COHERE_API_KEY; dimensions is our target/column size (embed-v4.0 emits a
    // larger Matryoshka vector that the client truncates to fit — see V6 migration's vector(768)).
    public record Cohere(String apiKey, String model, int dimensions) { }

    public record Google(String apiKey, String model) { }

    // baseUrl points at the local Ollama server; dimensions is the vector length we ask for.
    public record Ollama(String baseUrl, String model, int dimensions) { }
}
