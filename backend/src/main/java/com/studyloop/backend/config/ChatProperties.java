package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for the RAG chat model. `provider` is "cohere" for now (the only implementation);
// the Cohere key is shared with embeddings (COHERE_API_KEY). A blank key leaves the client
// "unconfigured" so the chat endpoint returns a clear error instead of crashing at startup.
// `minSimilarity` is the confidence gate: if the best retrieved chunk's cosine similarity falls
// below it (and nothing matched lexically either), chat refuses instead of letting the model
// answer from weak context. 0 disables the gate.
@ConfigurationProperties(prefix = "studyloop.chat")
public record ChatProperties(String provider, Cohere cohere, double minSimilarity) {

    public record Cohere(String apiKey, String model) { }
}
