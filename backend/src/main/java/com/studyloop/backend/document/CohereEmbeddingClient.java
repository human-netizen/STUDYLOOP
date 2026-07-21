package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.studyloop.backend.config.EmbeddingProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

// Embeds text with Cohere's hosted embed API (embed-v4.0). A free trial key is limited by
// requests-per-minute (not tokens), so batching up to 96 chunks per call keeps a whole document
// to just a couple of calls — well under the limit that made Google's free tier return 429s.
// embed-v4.0 only emits 256/512/1024/1536-dim vectors and is Matryoshka-trained, so we request
// the smallest supported size that covers our column (1024 for vector(768)) and then truncate +
// renormalize down to fit. A blank key leaves the client "unconfigured" so the pipeline stores
// chunks without vectors instead of failing.
public class CohereEmbeddingClient implements EmbeddingClient {

    private static final String EMBED_URL = "https://api.cohere.com/v2/embed";
    private static final String DEFAULT_MODEL = "embed-v4.0";
    private static final int DEFAULT_DIMENSIONS = 768;
    // Cohere caps a single embed call at 96 inputs.
    private static final int MAX_BATCH = 96;
    // The only output sizes embed-v4.0 supports; we pick the smallest that covers our target.
    private static final int[] SUPPORTED_DIMENSIONS = {256, 512, 1024, 1536};

    private final RestClient restClient = RestClient.create();
    private final String apiKey;
    private final String model;
    private final int dimensions;         // our target size (must match the vector column)
    private final int requestDimension;   // the output_dimension we actually ask Cohere for

    public CohereEmbeddingClient(EmbeddingProperties properties) {
        EmbeddingProperties.Cohere cohere = properties.cohere();
        this.apiKey = cohere != null ? cohere.apiKey() : null;
        String configuredModel = cohere != null ? cohere.model() : null;
        this.model = (configuredModel == null || configuredModel.isBlank()) ? DEFAULT_MODEL : configuredModel;
        int configuredDims = cohere != null ? cohere.dimensions() : 0;
        this.dimensions = configuredDims > 0 ? configuredDims : DEFAULT_DIMENSIONS;
        this.requestDimension = smallestSupportedAtLeast(this.dimensions);
    }

    private static int smallestSupportedAtLeast(int target) {
        for (int dim : SUPPORTED_DIMENSIONS) {
            if (dim >= target) {
                return dim;
            }
        }
        return SUPPORTED_DIMENSIONS[SUPPORTED_DIMENSIONS.length - 1];
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!isConfigured()) {
            throw new EmbeddingException("Cohere embedding API key is not configured.");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH) {
            vectors.addAll(embedBatch(texts.subList(start, Math.min(start + MAX_BATCH, texts.size()))));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch) {
        // input_type=search_document: these are the documents we index (queries use search_query).
        EmbedRequest request =
                new EmbedRequest(model, "search_document", batch, List.of("float"), requestDimension);

        JsonNode response;
        try {
            response = restClient.post()
                    .uri(EMBED_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new EmbeddingException("Cohere embedding request failed: " + e.getMessage(), e);
        }

        JsonNode embeddings = response != null ? response.get("embeddings") : null;
        JsonNode floats = embeddings != null ? embeddings.get("float") : null;
        if (floats == null || !floats.isArray() || floats.size() != batch.size()) {
            throw new EmbeddingException("Unexpected embedding response from Cohere.");
        }
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (JsonNode values : floats) {
            if (values == null || !values.isArray()) {
                throw new EmbeddingException("Embedding response was missing vector values.");
            }
            vectors.add(fitToDimensions(values));
        }
        return vectors;
    }

    // Truncate the returned vector to our column size and renormalize. embed-v4.0 is Matryoshka,
    // so a leading slice of the vector is itself a valid (shorter) embedding.
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

    // Cohere v2/embed request shape (field names match the wire format, so no annotations needed).
    private record EmbedRequest(String model, String input_type, List<String> texts,
                                List<String> embedding_types, int output_dimension) { }
}
