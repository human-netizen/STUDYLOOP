package com.studyloop.backend.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.config.ChatProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageRecorder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

// Generates answers with Cohere's chat API (Command R). It's the only ChatClient, so it's a
// plain @Component. The request is a list of role/content messages (system prompt + history +
// question); the reply text is the first text block of the assistant message. A blank key
// leaves the client unconfigured so the service can refuse cleanly instead of erroring mid-call.
//
// Every successful call is reported to AiUsageRecorder with the token counts Cohere billed, which
// is what /admin/costs is built from. Reading them here rather than estimating them elsewhere is
// the difference between a real invoice and a guess.
@Component
public class CohereChatClient implements ChatClient {

    private static final String CHAT_URL = "https://api.cohere.com/v2/chat";
    private static final String DEFAULT_MODEL = "command-r-08-2024";
    private static final String PROVIDER = "cohere";

    private final RestClient restClient = RestClient.create();
    // Cohere adds response fields over time and we only read a few; failing on the rest would
    // turn a harmless API addition into an outage.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final AiUsageRecorder usageRecorder;
    private final String apiKey;
    private final String model;

    public CohereChatClient(ChatProperties properties, AiUsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder;
        ChatProperties.Cohere cohere = properties.cohere();
        this.apiKey = cohere != null ? cohere.apiKey() : null;
        String configuredModel = cohere != null ? cohere.model() : null;
        this.model = (configuredModel == null || configuredModel.isBlank()) ? DEFAULT_MODEL : configuredModel;
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String complete(List<LlmMessage> messages) {
        if (!isConfigured()) {
            throw new ChatException("Cohere chat API key is not configured.");
        }

        return send(new ChatRequest(model, messages, false, null), AiOperation.CHAT);
    }

    @Override
    public String completeJson(List<LlmMessage> messages) {
        if (!isConfigured()) {
            throw new ChatException("Cohere chat API key is not configured.");
        }

        // response_format json_object makes Cohere return a bare JSON object (no prose, no code
        // fences), so the caller can parse the reply directly.
        //
        // Four features share this method — summaries, quizzes, grading, flashcards — so it can't
        // name the operation itself. Each caller opens an AiUsageContext scope; OTHER is what a
        // caller that forgot looks like in the dashboard.
        return send(new ChatRequest(model, messages, false, ResponseFormat.jsonObject()), AiOperation.OTHER);
    }

    // Shared non-streaming call: posts the request, reads the first text block of the reply.
    private String send(ChatRequest request, AiOperation operation) {
        ChatCompletion response;
        try {
            response = restClient.post()
                    .uri(CHAT_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ChatCompletion.class);
        } catch (RestClientException e) {
            throw new ChatException("Cohere chat request failed: " + e.getMessage(), e);
        }

        // A null response can't produce text, so reaching past this point means it isn't null.
        String text = firstText(response);
        if (text == null || text.isBlank()) {
            throw new ChatException("Cohere chat returned an empty response.");
        }
        recordUsage(operation, response.usage());
        return text.trim();
    }

    @Override
    public String streamComplete(List<LlmMessage> messages, Consumer<String> onDelta) {
        if (!isConfigured()) {
            throw new ChatException("Cohere chat API key is not configured.");
        }

        ChatRequest request = new ChatRequest(model, messages, true, null);
        StreamResult result;
        try {
            // exchange() gives us the live response stream (no buffering), so we can read
            // Cohere's server-sent events line by line and forward each token as it lands.
            result = restClient.post()
                    .uri(CHAT_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(request)
                    .exchange((clientRequest, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new ChatException(
                                    "Cohere chat request failed: HTTP " + response.getStatusCode().value());
                        }
                        return readStream(response.getBody(), onDelta);
                    });
        } catch (RestClientException e) {
            throw new ChatException("Cohere chat request failed: " + e.getMessage(), e);
        }

        if (result == null || result.text().isBlank()) {
            throw new ChatException("Cohere chat returned an empty response.");
        }
        usageRecorder.record(PROVIDER, model, AiOperation.CHAT_STREAM,
                result.inputTokens(), result.outputTokens());
        return result.text().trim();
    }

    // Reads the SSE body: each event is a "data: {json}" line. "content-delta" events carry the
    // tokens; the closing "message-end" event carries the billed token counts, which is the only
    // place a streamed call reports what it cost.
    private StreamResult readStream(java.io.InputStream body, Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        int inputTokens = 0;
        int outputTokens = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String json = line.substring("data:".length()).trim();
                if (json.isEmpty() || "[DONE]".equals(json)) {
                    continue;
                }
                JsonNode event = parse(json);
                if (event == null) {
                    continue;
                }
                String type = event.path("type").asText();
                if ("content-delta".equals(type)) {
                    JsonNode text = event.path("delta").path("message").path("content").path("text");
                    if (!text.isMissingNode() && !text.asText().isEmpty()) {
                        full.append(text.asText());
                        onDelta.accept(text.asText());
                    }
                } else if ("message-end".equals(type)) {
                    JsonNode billed = event.path("delta").path("usage").path("billed_units");
                    inputTokens = billed.path("input_tokens").asInt(0);
                    outputTokens = billed.path("output_tokens").asInt(0);
                }
            }
        } catch (IOException e) {
            throw new ChatException("Cohere chat stream read failed: " + e.getMessage(), e);
        }
        return new StreamResult(full.toString(), inputTokens, outputTokens);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            // A malformed event line is skipped rather than aborting the whole stream.
            return null;
        }
    }

    // A call whose usage Cohere didn't report is still recorded, at zero tokens: the call count
    // stays honest, and a column of zero-cost calls is a visible sign to go look at why.
    private void recordUsage(AiOperation operation, Usage usage) {
        BilledUnits billed = usage == null ? null : usage.billedUnits();
        usageRecorder.record(PROVIDER, model, operation,
                billed == null ? 0 : billed.input(), billed == null ? 0 : billed.output());
    }

    // The assistant message carries a list of content blocks; we want the first text block.
    private static String firstText(ChatCompletion response) {
        if (response == null || response.message() == null || response.message().content() == null) {
            return null;
        }
        for (Content block : response.message().content()) {
            if (block != null && "text".equals(block.type()) && block.text() != null) {
                return block.text();
            }
        }
        return null;
    }

    // Cohere v2/chat request shape — field names match the wire format. `stream` toggles the
    // token-by-token SSE response used by streamComplete; `responseFormat` (omitted when null)
    // switches the reply to a bare JSON object for completeJson.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ChatRequest(String model, List<LlmMessage> messages, boolean stream,
                               @JsonProperty("response_format") ResponseFormat responseFormat) { }

    // Cohere's structured-output selector; type "json_object" forces a single JSON object reply.
    private record ResponseFormat(String type) {
        static ResponseFormat jsonObject() {
            return new ResponseFormat("json_object");
        }
    }

    // Cohere v2/chat response shape. We read message.content[].text and the billed token counts.
    private record ChatCompletion(Message message, Usage usage) { }

    private record Message(List<Content> content) { }

    private record Content(String type, String text) { }

    private record Usage(@JsonProperty("billed_units") BilledUnits billedUnits) { }

    // Cohere reports billed units as JSON numbers that are not always integral, so they're read
    // as Double and truncated rather than failing to bind.
    private record BilledUnits(@JsonProperty("input_tokens") Double inputTokens,
                               @JsonProperty("output_tokens") Double outputTokens) {

        int input() {
            return inputTokens == null ? 0 : inputTokens.intValue();
        }

        int output() {
            return outputTokens == null ? 0 : outputTokens.intValue();
        }
    }

    // A finished stream: the concatenated answer plus what Cohere said it billed for.
    private record StreamResult(String text, int inputTokens, int outputTokens) { }
}
