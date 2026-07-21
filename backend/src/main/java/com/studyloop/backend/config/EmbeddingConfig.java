package com.studyloop.backend.config;

import com.studyloop.backend.document.EmbeddingClient;
import com.studyloop.backend.document.GoogleEmbeddingClient;
import com.studyloop.backend.document.OllamaEmbeddingClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Picks the single EmbeddingClient bean from studyloop.embedding.provider. Selecting the
// implementation here (rather than annotating each client @Component) keeps exactly one bean
// in the context, so DocumentEmbeddingService can inject EmbeddingClient without ambiguity.
// Default: the local, offline Ollama client — no API key, no rate limits.
@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingClient embeddingClient(EmbeddingProperties properties) {
        String provider = properties.provider();
        if (provider != null && provider.equalsIgnoreCase("google")) {
            return new GoogleEmbeddingClient(properties);
        }
        return new OllamaEmbeddingClient(properties);
    }
}
