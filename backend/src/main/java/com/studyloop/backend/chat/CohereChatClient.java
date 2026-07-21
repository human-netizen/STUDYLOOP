package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.config.ChatProperties;
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
@Component
public class CohereChatClient implements ChatClient {

    private static final String CHAT_URL = "https://api.cohere.com/v2/chat";
    private static final String DEFAULT_MODEL = "command-r-08-2024";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public CohereChatClient(ChatProperties properties) {
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

        ChatRequest request = new ChatRequest(model, messages, false);
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

        String text = firstText(response);
        if (text == null || text.isBlank()) {
            throw new ChatException("Cohere chat returned an empty response.");
        }
        return text.trim();
    }

    @Override
    public String streamComplete(List<LlmMessage> messages, Consumer<String> onDelta) {
        if (!isConfigured()) {
            throw new ChatException("Cohere chat API key is not configured.");
        }

        ChatRequest request = new ChatRequest(model, messages, true);
        String answer;
        try {
            // exchange() gives us the live response stream (no buffering), so we can read
            // Cohere's server-sent events line by line and forward each token as it lands.
            answer = restClient.post()
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

        if (answer == null || answer.isBlank()) {
            throw new ChatException("Cohere chat returned an empty response.");
        }
        return answer.trim();
    }

    // Reads the SSE body: each event is a "data: {json}" line. We only care about
    // "content-delta" events, whose token sits at delta.message.content.text.
    private String readStream(java.io.InputStream body, Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
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
                String token = extractDelta(json);
                if (token != null && !token.isEmpty()) {
                    full.append(token);
                    onDelta.accept(token);
                }
            }
        } catch (IOException e) {
            throw new ChatException("Cohere chat stream read failed: " + e.getMessage(), e);
        }
        return full.toString();
    }

    private String extractDelta(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!"content-delta".equals(node.path("type").asText())) {
                return null;
            }
            JsonNode text = node.path("delta").path("message").path("content").path("text");
            return text.isMissingNode() ? null : text.asText();
        } catch (IOException e) {
            // A malformed event line is skipped rather than aborting the whole stream.
            return null;
        }
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
    // token-by-token SSE response used by streamComplete.
    private record ChatRequest(String model, List<LlmMessage> messages, boolean stream) { }

    // Cohere v2/chat response shape. We only read message.content[].text.
    private record ChatCompletion(Message message) { }

    private record Message(List<Content> content) { }

    private record Content(String type, String text) { }
}
