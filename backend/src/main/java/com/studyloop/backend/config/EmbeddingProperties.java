package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for turning chunks into vectors. The API key arrives from the GOOGLE_API_KEY
// environment variable (empty when unset) — when blank, ingestion stores chunks without
// embeddings rather than failing, so the app still runs with no key configured.
@ConfigurationProperties(prefix = "studyloop.embedding")
public record EmbeddingProperties(Google google) {

    public record Google(String apiKey, String model) { }
}
