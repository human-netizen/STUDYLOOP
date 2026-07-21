package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for the RAG chat model. `provider` is "cohere" for now (the only implementation);
// the Cohere key is shared with embeddings (COHERE_API_KEY). A blank key leaves the client
// "unconfigured" so the chat endpoint returns a clear error instead of crashing at startup.
@ConfigurationProperties(prefix = "studyloop.chat")
public record ChatProperties(String provider, Cohere cohere) {

    public record Cohere(String apiKey, String model) { }
}
