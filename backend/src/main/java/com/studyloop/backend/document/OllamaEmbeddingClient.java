package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.studyloop.backend.config.EmbeddingProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

// Embeds text with a locally-running Ollama server: no API key, no rate limits, fully offline.
// Uses Ollama's OpenAI-compatible /v1/embeddings endpoint. Qwen3-Embedding is a Matryoshka
// (MRL) model, so its vector can be shortened to our 768-dim column without retraining: we ask
// Ollama for `dimensions` directly and, as a safety net, truncate + renormalize if the server
// ever returns its native length (1024 for the 0.6b model) instead.
public class OllamaEmbeddingClient implements EmbeddingClient {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "qwen3-embedding:0.6b";
    private static final int DEFAULT_DIMENSIONS = 768;
    // Ollama embeds sequentially, so batching is only about request size / round-trip count.
    private static final int MAX_BATCH = 64;

    private final RestClient restClient;
    private final String model;
    private final int dimensions;

    public OllamaEmbeddingClient(EmbeddingProperties properties) {
        EmbeddingProperties.Ollama ollama = properties.ollama();
        String baseUrl = ollama != null && ollama.baseUrl() != null && !ollama.baseUrl().isBlank()
                ? ollama.baseUrl() : DEFAULT_BASE_URL;
        String configuredModel = ollama != null ? ollama.model() : null;
        this.model = (configuredModel == null || configuredModel.isBlank()) ? DEFAULT_MODEL : configuredModel;
        int configuredDims = ollama != null ? ollama.dimensions() : 0;
        this.dimensions = configuredDims > 0 ? configuredDims : DEFAULT_DIMENSIONS;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    // Ollama is local and keyless, so it is always "configured". If the server is down or the
    // model isn't pulled, embed() throws (the document is marked FAILED with a clear reason)
    // rather than silently storing chunks without vectors.
    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH) {
            vectors.addAll(embedBatch(texts.subList(start, Math.min(start + MAX_BATCH, texts.size()))));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch) {
        EmbedRequest request = new EmbedRequest(model, batch, dimensions);

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/v1/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new EmbeddingException(
                    "Ollama embedding request failed (is Ollama running and the model pulled?): "
                            + e.getMessage(), e);
        }

        JsonNode data = response != null ? response.get("data") : null;
        if (data == null || !data.isArray() || data.size() != batch.size()) {
            throw new EmbeddingException("Unexpected embedding response from Ollama.");
        }
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (JsonNode item : data) {
            JsonNode values = item.get("embedding");
            if (values == null || !values.isArray()) {
                throw new EmbeddingException("Embedding response was missing vector values.");
            }
            vectors.add(fitToDimensions(values));
        }
        return vectors;
    }

    // Coerce the returned vector to exactly `dimensions`. Because Qwen3-Embedding is MRL-trained,
    // keeping the leading `dimensions` components and renormalizing yields a valid shorter vector
    // if Ollama ever returns its native length rather than honoring the requested dimensions.
    private float[] fitToDimensions(JsonNode values) {
        int returned = values.size();
        if (returned < dimensions) {
            throw new EmbeddingException(
                    "Embedding vector too short: expected " + dimensions + ", got " + returned);
        }
        float[] vector = new float[dimensions];
        double sumSquares = 0.0;
        for (int i = 0; i < dimensions; i++) {
            float v = (float) values.get(i).asDouble();
            vector[i] = v;
            sumSquares += (double) v * v;
        }
        if (returned > dimensions && sumSquares > 0) {
            float norm = (float) Math.sqrt(sumSquares);
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    // OpenAI-compatible request shape. `dimensions` asks Ollama to return an MRL-truncated vector.
    private record EmbedRequest(String model, List<String> input, int dimensions) { }
}
