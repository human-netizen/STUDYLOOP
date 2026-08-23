package com.studyloop.backend.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyloop.backend.config.EmbeddingProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

// Embeds text with Cohere's hosted embed API (embed-v4.0). A free trial key is limited by
// requests-per-minute (not tokens), so batching up to 96 chunks per call keeps a whole document
// to just a couple of calls — well under the limit that made Google's free tier return 429s.
// embed-v4.0 only emits 256/512/1024/1536-dim vectors and is Matryoshka-trained, so we request
// the smallest supported size that covers our column (1024 for vector(768)) and then truncate +
// renormalize down to fit. A blank key leaves the client "unconfigured" so the pipeline stores
// chunks without vectors instead of failing.
public class CohereEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(CohereEmbeddingClient.class);

    private static final String EMBED_URL = "https://api.cohere.com/v2/embed";
    private static final String DEFAULT_MODEL = "embed-v4.0";
    private static final int DEFAULT_DIMENSIONS = 768;
    // Cohere caps a single embed call at 96 inputs.
    private static final int MAX_BATCH = 96;
    // How many times a document's embedding call will wait out a rate limit before giving up. Four
    // tries spread over ten minutes covers a trial key's per-minute token budget several times
    // over; past that the problem is not a burst and failing the document is the honest answer.
    private static final int MAX_RATE_LIMIT_RETRIES = 4;
    private static final Duration RETRY_WAIT = Duration.ofSeconds(30);
    // The only output sizes embed-v4.0 supports; we pick the smallest that covers our target.
    private static final int[] SUPPORTED_DIMENSIONS = {256, 512, 1024, 1536};

    // How many page images go into one embed call (Phase 17.1). Far below the 96 the text side
    // uses, and the limit that matters is bytes rather than inputs: a 120-DPI page is a few hundred
    // kilobytes of PNG and base64 adds a third on top, so eight of them is a request measured in
    // megabytes. Batching at all is worth it because a trial key is limited by *calls* per minute.
    private static final int MAX_IMAGE_BATCH = 8;
    // Ceiling on one request's base64 payload, which closes the batch early when the pages are
    // heavy. A batch is whichever limit is reached first.
    private static final int MAX_IMAGE_BATCH_BYTES = 6 * 1024 * 1024;
    private static final String PNG_DATA_URI_PREFIX = "data:image/png;base64,";

    private static final String PROVIDER = "cohere";

    // The model name the *dashboard* files image embeddings under. The wire model is the same
    // embed-v4.0 the text side uses — this string is never sent anywhere — but Cohere bills image
    // tokens at a different rate from text tokens, and the price table is keyed by model name. One
    // entry for both would price every page render at the text rate and quietly understate the
    // only part of ingestion whose cost scales with how many figures a course uploads.
    private static final String IMAGE_PRICING_MODEL_SUFFIX = "-image";

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

    // Phase 17.1 — embed-v4.0 takes pictures, and it takes them into the space it already puts text
    // in. That sentence is the whole feature: no second provider, no second key, no second column,
    // no dimension change, no new index, and no separate query embedding — the question a student
    // types is embedded exactly as it was before and compared against a page nobody described.
    @Override
    public boolean supportsImages() {
        return true;
    }

    // One vector per page image, in order.
    //
    // `search_document` rather than an image-specific input type, because these *are* the documents
    // being indexed and the query side has to stay `search_query` to match them. embed-v4.0 accepts
    // both alongside images; earlier Cohere models had a separate `image` input type that could
    // only be compared against itself, which is the thing not to reproduce here.
    @Override
    public List<float[]> embedImages(List<byte[]> pngImages) {
        if (!isConfigured()) {
            throw new EmbeddingException("Cohere embedding API key is not configured.");
        }
        List<float[]> vectors = new ArrayList<>(pngImages.size());
        List<String> batch = new ArrayList<>();
        long batchBytes = 0;
        for (byte[] png : pngImages) {
            String uri = PNG_DATA_URI_PREFIX + Base64.getEncoder().encodeToString(png);
            // Closed on whichever limit is reached first, and closed *before* adding, so a single
            // oversized page goes on its own rather than being dropped for not fitting.
            if (!batch.isEmpty()
                    && (batch.size() >= MAX_IMAGE_BATCH || batchBytes + uri.length() > MAX_IMAGE_BATCH_BYTES)) {
                vectors.addAll(callEmbedImages(batch));
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(uri);
            batchBytes += uri.length();
        }
        if (!batch.isEmpty()) {
            vectors.addAll(callEmbedImages(batch));
        }
        return vectors;
    }

    // Images go through the same rate-limit wait document text gets, and for the same reason: this
    // only ever runs at ingest, behind the status machine, with nobody holding a connection open.
    private List<float[]> callEmbedImages(List<String> dataUris) {
        for (int attempt = 0; ; attempt++) {
            try {
                return requestImageEmbeddings(dataUris);
            } catch (EmbeddingException e) {
                if (attempt >= MAX_RATE_LIMIT_RETRIES || !isRateLimited(e)) {
                    throw e;
                }
                long wait = RETRY_WAIT.toMillis() * (attempt + 1);
                log.warn("Cohere rate-limited the image embedding request; waiting {}s and retrying ({}/{})",
                        wait / 1000, attempt + 1, MAX_RATE_LIMIT_RETRIES);
                sleep(wait);
            }
        }
    }

    private List<float[]> requestImageEmbeddings(List<String> dataUris) {
        // The v4 request shape: `inputs`, a list of content-block arrays, rather than the flat
        // `texts` array the rest of this class sends. Cohere still accepts a top-level `images`
        // list, and it is the older one-space-per-modality form — this is the shape that also lets
        // a single input carry text and a picture together, which is where 17.2 would go next.
        List<EmbedInput> inputs = dataUris.stream()
                .map(uri -> new EmbedInput(List.of(ImageBlock.of(uri))))
                .toList();
        ImageEmbedRequest request = new ImageEmbedRequest(
                model, "search_document", inputs, List.of("float"), requestDimension);

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
            throw new EmbeddingException("Cohere image embedding request failed: " + e.getMessage(), e);
        }

        List<float[]> floats = response != null && response.embeddings() != null
                ? response.embeddings().floats() : null;
        if (floats == null || floats.size() != dataUris.size()) {
            throw new EmbeddingException("Unexpected image embedding response from Cohere.");
        }
        List<float[]> vectors = new ArrayList<>(dataUris.size());
        for (float[] values : floats) {
            if (values == null) {
                throw new EmbeddingException("Image embedding response was missing vector values.");
            }
            vectors.add(fitToDimensions(values));
        }

        // **billed_units.input_tokens is zero on an image call, and image_tokens carries the
        // count.** Reading the wrong one is a silent zero: the ledger would record a real call, at
        // a real price, for nothing — and the one thing the cost dashboard exists to answer is what
        // the expensive-looking part of the pipeline actually costs.
        usageRecorder.record(PROVIDER, model + IMAGE_PRICING_MODEL_SUFFIX,
                AiOperation.EMBED_IMAGES, response.billedImageTokens(), 0);
        return vectors;
    }

    // Waits out the provider's rate limit and tries again — but only when indexing a document.
    //
    // The limit that forced this is a token-per-minute one (100,000 on a trial key), so it is
    // reached by uploading a long PDF or a few short ones together, not by anything unusual. Before
    // this, that came back as a FAILED document with "429 Too Many Requests" on it: a genuine
    // upload, correctly extracted, marked broken for a reason the student can neither understand
    // nor act on, and with no fallback behind it — a chunk that never got a vector is invisible to
    // semantic search for as long as it exists.
    //
    // Deliberately not applied to `embedQuery`, and the distinction is the same one Phase 12.1 made
    // when it refused to retry inside the rerank client. Ingestion is asynchronous, has a status
    // machine in front of it, and nobody is waiting; a query is on the request path in front of a
    // streaming answer, where a minute of sleeping is worse than a slightly weaker result.
    private List<float[]> embedBatch(List<String> batch, String inputType, AiOperation operation) {
        for (int attempt = 0; ; attempt++) {
            try {
                return callEmbed(batch, inputType, operation);
            } catch (EmbeddingException e) {
                boolean retryable = operation == AiOperation.EMBED_DOCUMENTS
                        && attempt < MAX_RATE_LIMIT_RETRIES
                        && isRateLimited(e);
                if (!retryable) {
                    throw e;
                }
                // Linear, not exponential: the window this is waiting out is a fixed minute, so
                // doubling would spend the second retry asleep long after the limit had reset.
                long wait = RETRY_WAIT.toMillis() * (attempt + 1);
                log.warn("Cohere rate-limited the embedding request; waiting {}s and retrying ({}/{})",
                        wait / 1000, attempt + 1, MAX_RATE_LIMIT_RETRIES);
                sleep(wait);
            }
        }
    }

    private static boolean isRateLimited(EmbeddingException e) {
        String message = e.getMessage();
        return message != null && (message.contains("429") || message.contains("rate limit"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Embedding was interrupted while waiting out a rate limit.");
        }
    }

    private List<float[]> callEmbed(List<String> batch, String inputType, AiOperation operation) {
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

    // The same endpoint with `inputs` in place of `texts` (Phase 17.1). Two record types rather
    // than one with both fields nullable, because Jackson would then have to be told to drop the
    // null — and a request carrying `"texts": null` beside a populated `inputs` is a shape the
    // provider is under no obligation to accept.
    private record ImageEmbedRequest(String model, String input_type, List<EmbedInput> inputs,
                                     List<String> embedding_types, int output_dimension) { }

    private record EmbedInput(List<ImageBlock> content) { }

    // A content block. `type` names the block and `image_url.url` carries the picture as a
    // `data:image/png;base64,...` URI — the same shape as a remote image reference, with the bytes
    // inline instead of a host to fetch them from, so nothing has to be uploaded anywhere first.
    private record ImageBlock(String type, ImageUrl image_url) {

        static ImageBlock of(String dataUri) {
            return new ImageBlock("image_url", new ImageUrl(dataUri));
        }
    }

    private record ImageUrl(String url) { }

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

        // The count an image call reports. Measured against the live API rather than assumed: an
        // image request comes back with `input_tokens: 0` and the real number in `image_tokens`,
        // so a client reading the field above would file every page render as a free call.
        int billedImageTokens() {
            if (meta == null || meta.billedUnits() == null || meta.billedUnits().imageTokens() == null) {
                return 0;
            }
            return meta.billedUnits().imageTokens().intValue();
        }
    }

    private record Embeddings(@JsonProperty("float") List<float[]> floats) { }

    private record Meta(@JsonProperty("billed_units") BilledUnits billedUnits) { }

    private record BilledUnits(@JsonProperty("input_tokens") Double inputTokens,
                               @JsonProperty("image_tokens") Double imageTokens) { }
}
