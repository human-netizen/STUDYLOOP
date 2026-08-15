package com.studyloop.backend.config;

import com.studyloop.backend.document.CohereEmbeddingClient;
import com.studyloop.backend.document.EmbeddingClient;
import com.studyloop.backend.document.GoogleEmbeddingClient;
import com.studyloop.backend.document.OllamaEmbeddingClient;
import com.studyloop.backend.usage.AiUsageRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Picks the single EmbeddingClient bean from studyloop.embedding.provider. Selecting the
// implementation here (rather than annotating each client @Component) keeps exactly one bean
// in the context, so DocumentEmbeddingService can inject EmbeddingClient without ambiguity.
// Default: the hosted Cohere client. "google" is the other hosted option; "ollama" runs locally.
@Configuration
public class EmbeddingConfig {

    // Only the Cohere client is metered. Ollama runs on the user's own machine and bills nothing,
    // and Google's embed response doesn't report token counts, so a row for either would be a
    // call with a fabricated price — worse for the dashboard than an honest absence.
    @Bean
    public EmbeddingClient embeddingClient(EmbeddingProperties properties, AiUsageRecorder usageRecorder) {
        String provider = properties.provider();
        if (provider != null && provider.equalsIgnoreCase("google")) {
            return new GoogleEmbeddingClient(properties);
        }
        if (provider != null && provider.equalsIgnoreCase("ollama")) {
            return new OllamaEmbeddingClient(properties);
        }
        return new CohereEmbeddingClient(properties, usageRecorder);
    }
}
