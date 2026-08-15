package com.studyloop.backend.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyloop.backend.config.EmbeddingProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageRecorder;
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

    private static final String PROVIDER = "cohere";

    private final RestClient restClient = RestClient.create();
    private final AiUsageRecorder usageRecorder;
    private final String apiKey;
    private final String model;
    private final int dimensions;         // our target size (must match the vector column)
    private final int requestDimension;   // the output_dimension we actually ask Cohere for

    public CohereEmbeddingClient(EmbeddingProperties properties, AiUsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder;
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
            // search_document: these are the documents we index.
            vectors.addAll(embedBatch(texts.subList(start, Math.min(start + MAX_BATCH, texts.size())),
                    "search_document", AiOperation.EMBED_DOCUMENTS));
        }
        return vectors;
    }

    @Override
    public float[] embedQuery(String text) {
        if (!isConfigured()) {
            throw new EmbeddingException("Cohere embedding API key is not configured.");
        }
        // search_query embeds a question into the same space as the search_document chunks.
        return embedBatch(List.of(text), "search_query", AiOperation.EMBED_QUERY).get(0);
    }

    private List<float[]> embedBatch(List<String> batch, String inputType, AiOperation operation) {
        EmbedRequest request =
                new EmbedRequest(model, inputType, batch, List.of("float"), requestDimension);

        EmbedResponse response;
        try {
            response = restClient.post()
                    .uri(EMBED_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(EmbedResponse.class);
        } catch (RestClientException e) {
            throw new EmbeddingException("Cohere embedding request failed: " + e.getMessage(), e);
        }

        List<float[]> floats = response != null && response.embeddings() != null
                ? response.embeddings().floats() : null;
        if (floats == null || floats.size() != batch.size()) {
            throw new EmbeddingException("Unexpected embedding response from Cohere.");
        }
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (float[] values : floats) {
            if (values == null) {
                throw new EmbeddingException("Embedding response was missing vector values.");
            }
            vectors.add(fitToDimensions(values));
        }

        // Embeddings bill on input only — there is no generated output to pay for. One record per
        // batch, not per text, because a batch is one billable request.
        usageRecorder.record(PROVIDER, model, operation, response.billedInputTokens(), 0);
        return vectors;
    }

    // Truncate the returned vector to our column size and renormalize. embed-v4.0 is Matryoshka,
    // so a leading slice of the vector is itself a valid (shorter) embedding.
    private float[] fitToDimensions(float[] values) {
        int returned = values.length;
        if (returned < dimensions) {
            throw new EmbeddingException(
                    "Embedding vector too short: expected " + dimensions + ", got " + returned);
        }
        float[] vector = new float[dimensions];
        double sumSquares = 0.0;
        for (int i = 0; i < dimensions; i++) {
            float v = values[i];
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

    // Cohere v2/embed response shape. We need embeddings.float — a list of vectors — and the
    // billed token count that meta carries, which is what the cost dashboard is built from.
    // Jackson deserializes each JSON number array straight into a float[]. "float" is a Java
    // keyword, so the field is named `floats` and mapped to the wire name via @JsonProperty.
    private record EmbedResponse(Embeddings embeddings, Meta meta) {

        // Zero when the provider didn't report it: the call still gets a row, priced at nothing,
        // which shows up as a visible gap rather than a silently missing call.
        int billedInputTokens() {
            if (meta == null || meta.billedUnits() == null || meta.billedUnits().inputTokens() == null) {
                return 0;
            }
            return meta.billedUnits().inputTokens().intValue();
        }
    }

    private record Embeddings(@JsonProperty("float") List<float[]> floats) { }

    private record Meta(@JsonProperty("billed_units") BilledUnits billedUnits) { }

    private record BilledUnits(@JsonProperty("input_tokens") Double inputTokens) { }
}
