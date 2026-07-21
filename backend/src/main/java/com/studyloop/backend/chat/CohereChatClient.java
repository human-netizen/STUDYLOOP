package com.studyloop.backend.chat;

import com.studyloop.backend.config.ChatProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

// Generates answers with Cohere's chat API (Command R). It's the only ChatClient, so it's a
// plain @Component. The request is a list of role/content messages (system prompt + history +
// question); the reply text is the first text block of the assistant message. A blank key
// leaves the client unconfigured so the service can refuse cleanly instead of erroring mid-call.
@Component
public class CohereChatClient implements ChatClient {

    private static final String CHAT_URL = "https://api.cohere.com/v2/chat";
    private static final String DEFAULT_MODEL = "command-r-08-2024";

    private final RestClient restClient = RestClient.create();
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

        ChatRequest request = new ChatRequest(model, messages);
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

    // Cohere v2/chat request shape — field names match the wire format.
    private record ChatRequest(String model, List<LlmMessage> messages) { }

    // Cohere v2/chat response shape. We only read message.content[].text.
    private record ChatCompletion(Message message) { }

    private record Message(List<Content> content) { }

    private record Content(String type, String text) { }
}
