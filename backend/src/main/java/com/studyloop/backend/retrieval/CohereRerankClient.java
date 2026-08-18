package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyloop.backend.config.RetrievalProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageRecorder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

// Reranks with Cohere's hosted rerank API (rerank-v3.5). One HTTP call, no index, no migration:
// the whole stage is a POST with the query and the candidate passages, and a list of positions
// back. The key is the same COHERE_API_KEY embeddings and chat already use, so enabling the stage
// costs configuration and nothing else.
//
// Billing is per *search*, not per token — one call over up to 100 passages is one search unit —
// which is why this reports through recordSearch rather than the token path everything else uses.
@Component
public class CohereRerankClient implements RerankClient {

    private static final String RERANK_URL = "https://api.cohere.com/v2/rerank";
    private static final String PROVIDER = "cohere";

    private final RestClient restClient = RestClient.create();
    private final AiUsageRecorder usageRecorder;
    private final String apiKey;
    private final String model;

    public CohereRerankClient(RetrievalProperties properties, AiUsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder;
        this.apiKey = properties.rerank().apiKey();
        this.model = properties.rerank().model();
    }

    // A blank key leaves the stage disabled rather than failing requests. Retrieval already
    // degrades to full-text when the embedder is unconfigured; this follows the same rule.
    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<Ranked> rerank(String query, List<String> documents, int topN) {
        if (!isConfigured()) {
            throw new RerankException("Cohere rerank API key is not configured.");
        }
        if (documents.isEmpty()) {
            return List.of();
        }

        RerankRequest request =
                new RerankRequest(model, query, documents, Math.min(topN, documents.size()));
        RerankResponse response;
        try {
            response = restClient.post()
                    .uri(RERANK_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RerankResponse.class);
        } catch (RestClientException e) {
            throw new RerankException("Cohere rerank request failed: " + e.getMessage(), e);
        }

        if (response == null || response.results() == null) {
            throw new RerankException("Unexpected rerank response from Cohere.");
        }
        usageRecorder.recordSearch(PROVIDER, model, AiOperation.RERANK, response.billedSearchUnits());
        return response.results().stream()
                .map(result -> new Ranked(result.index(), result.relevanceScore()))
                .toList();
    }

    // Cohere v2/rerank request shape — field names match the wire format. v2 takes documents as
    // plain strings; there is no per-document metadata to send, because the ranking comes back as
    // positions into this same list.
    private record RerankRequest(String model, String query, List<String> documents,
                                 @JsonProperty("top_n") int topN) { }

    private record RerankResponse(List<Result> results, Meta meta) {

        // Defaults to 1, not 0, and that difference matters: a rerank call's entire cost is its
        // search units, so reading a missing figure as zero would file a real call as free. Chat
        // and embeddings default to 0 tokens for the opposite reason — there the count is the only
        // thing at stake, and a visible zero is a prompt to go and look.
        int billedSearchUnits() {
            if (meta == null || meta.billedUnits() == null || meta.billedUnits().searchUnits() == null) {
                return 1;
            }
            return Math.max(meta.billedUnits().searchUnits().intValue(), 1);
        }
    }

    private record Result(int index, @JsonProperty("relevance_score") double relevanceScore) { }

    private record Meta(@JsonProperty("billed_units") BilledUnits billedUnits) { }

    private record BilledUnits(@JsonProperty("search_units") Double searchUnits) { }
}
