package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.studyloop.backend.config.EmbeddingProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

// Embeds text with Google's Generative Language API (text-embedding-004, 768-dim). Requests
// go out in batches; a missing key leaves the client "unconfigured" so the pipeline skips
// embedding rather than failing. Only exercised end to end in manual smoke tests (the key
// isn't available in CI); the storage path is covered with a stub client.
@Component
public class GoogleEmbeddingClient implements EmbeddingClient {

    private static final String DEFAULT_MODEL = "text-embedding-004";
    // Google caps batchEmbedContents at 100 requests per call.
    private static final int MAX_BATCH = 100;

    private final RestClient restClient = RestClient.create();
    private final String apiKey;
    private final String model;

    public GoogleEmbeddingClient(EmbeddingProperties properties) {
        EmbeddingProperties.Google google = properties.google();
        this.apiKey = google != null ? google.apiKey() : null;
        String configured = google != null ? google.model() : null;
        this.model = (configured == null || configured.isBlank()) ? DEFAULT_MODEL : configured;
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!isConfigured()) {
            throw new EmbeddingException("Google embedding API key is not configured.");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH) {
            vectors.addAll(embedBatch(texts.subList(start, Math.min(start + MAX_BATCH, texts.size()))));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch) {
        String qualifiedModel = "models/" + model;
        BatchRequest request = new BatchRequest(batch.stream()
                .map(text -> new EmbedRequest(qualifiedModel, new Content(List.of(new Part(text)))))
                .toList());

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("https://generativelanguage.googleapis.com/v1beta/models/{model}:batchEmbedContents?key={key}",
                            model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new EmbeddingException("Google embedding request failed: " + e.getMessage(), e);
        }

        JsonNode embeddings = response != null ? response.get("embeddings") : null;
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != batch.size()) {
            throw new EmbeddingException("Unexpected embedding response from Google.");
        }
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (JsonNode embedding : embeddings) {
            JsonNode values = embedding.get("values");
            if (values == null || !values.isArray()) {
                throw new EmbeddingException("Embedding response was missing vector values.");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    // Request shapes for the Generative Language API (responses are read as JsonNode).
    private record BatchRequest(List<EmbedRequest> requests) { }
    private record EmbedRequest(String model, Content content) { }
    private record Content(List<Part> parts) { }
    private record Part(String text) { }
}
