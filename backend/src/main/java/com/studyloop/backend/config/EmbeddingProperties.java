package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for turning chunks into vectors. `provider` selects which backend embeds text:
// "ollama" (default) runs a local, offline model with no API key or rate limits; "google"
// calls Google's hosted API using GOOGLE_API_KEY. When Google is selected but the key is
// blank, ingestion stores chunks without embeddings rather than failing.
@ConfigurationProperties(prefix = "studyloop.embedding")
public record EmbeddingProperties(String provider, Google google, Ollama ollama) {

    public record Google(String apiKey, String model) { }

    // baseUrl points at the local Ollama server; dimensions is the vector length we ask for
    // (must match the pgvector column — see V6 migration).
    public record Ollama(String baseUrl, String model, int dimensions) { }
}
