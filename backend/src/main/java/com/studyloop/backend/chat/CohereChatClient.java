package com.studyloop.backend.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.config.ChatProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    private static final Logger log = LoggerFactory.getLogger(CohereChatClient.class);

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
    private final String chatUrl;

    // @Autowired names the one Spring is to use. Without it a second constructor makes this
    // class uninstantiable - the container stops guessing the moment there is a choice, and the
    // failure is at context startup rather than at compile time.
    @Autowired
    public CohereChatClient(ChatProperties properties, AiUsageRecorder usageRecorder) {
        this(properties, usageRecorder, CHAT_URL);
    }

    // The same client pointed somewhere else, which exists for one reason: Phase 26.3 added a
    // *wire protocol* to this class - four streamed event types accumulating a tool call across
    // several fragments - and a protocol that is only exercised against the live provider is a
    // protocol nothing checks. The suite serves canned SSE from a local socket and reads what this
    // parses out of it.
    CohereChatClient(ChatProperties properties, AiUsageRecorder usageRecorder, String chatUrl) {
        this.usageRecorder = usageRecorder;
        ChatProperties.Cohere cohere = properties.cohere();
        this.apiKey = cohere != null ? cohere.apiKey() : null;
        String configuredModel = cohere != null ? cohere.model() : null;
        this.model = (configuredModel == null || configuredModel.isBlank()) ? DEFAULT_MODEL : configuredModel;
        this.chatUrl = chatUrl;
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

    // How many times one request may be sent before the caller is told it failed, and how long
    // to wait between tries.
    //
    // **Only a 5xx is retried.** A 4xx is a request this client will keep getting wrong — a bad
    // key, a spent trial allowance, a model name that does not exist — and repeating it wastes
    // three calls to learn what one already said. A read timeout is not retried either, for a
    // different reason: the provider may well have completed the work, so a second send is a
    // second bill for an answer that already exists.
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

    // Shared non-streaming call: posts the request, reads the first text block of the reply.
    //
    // **Retried, because the expensive callers are not one call but fourteen.** A chat turn that
    // hits a provider hiccup is a person pressing the button again; a video is up to fourteen
    // model calls behind a single POST, spread over minutes, and losing the whole job to one
    // transient 500 throws away every call that came before it. At a 2% failure rate per call, a
    // fourteen-call job fails a quarter of the time without this and 1 in 8000 with it.
    //
    // Not applied to streamComplete: a stream that has already delivered tokens cannot be replayed
    // from the start without the reader seeing the answer twice, and its caller is a person who
    // can ask again.
    private String send(ChatRequest request, AiOperation operation) {
        HttpServerErrorException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return sendOnce(request, operation);
            } catch (HttpServerErrorException e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    Duration wait = RETRY_BACKOFF.multipliedBy(attempt);
                    log.warn("Cohere returned {} on attempt {} of {}; retrying in {}s",
                            e.getStatusCode(), attempt, MAX_ATTEMPTS, wait.toSeconds());
                    pause(wait);
                }
            }
        }
        throw new ChatException(
                "Cohere chat request failed after " + MAX_ATTEMPTS + " attempts: " + lastFailure.getMessage(),
                lastFailure);
    }

    // Interrupting the thread cancels the request rather than sleeping through it: the caller here
    // may be the single video render thread, and a shutdown that has to wait out a backoff is a
    // shutdown that looks hung.
    private static void pause(Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatException("Interrupted while waiting to retry a Cohere chat request.", e);
        }
    }

    private String sendOnce(ChatRequest request, AiOperation operation) {
        ChatCompletion response;
        try {
            response = restClient.post()
                    .uri(chatUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ChatCompletion.class);
        } catch (HttpServerErrorException e) {
            // Rethrown as-is so send() can decide whether another attempt is worth making; every
            // other failure becomes a ChatException here and stops.
            throw e;
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

        return text(stream(new ChatRequest(model, messages, true, null), onDelta,
                AiOperation.CHAT_STREAM));
    }

    // Phase 26.3 - the model is offered one tool and decides whether to use it.
    //
    // **Two rounds at most, and the cap is structural.** The first request carries the tools; if
    // the model answers straight away, that answer is the turn and no second call is made. If it
    // asks for the tool, the result is appended and the conversation is re-sent **without the
    // tools** - so the second response cannot be another tool call, and there is no runaway loop
    // to bound with a counter that somebody has to keep correct.
    //
    // The two rounds bill separately on purpose: the first as CHAT_ROUTE, so /admin/costs can
    // answer "how often did the model decide to search", and the second as CHAT_STREAM, so a
    // grounded answer costs the same row it always did.
    @Override
    public String streamWithTools(List<LlmMessage> messages, List<ToolSpec> tools,
                                  ToolInvoker invoker, Consumer<String> onDelta) {
        if (!isConfigured()) {
            throw new ChatException("Cohere chat API key is not configured.");
        }

        List<Tool> offered = tools.stream().map(Tool::from).toList();
        StreamResult routed = stream(new ChatRequest(model, messages, true, null, offered), onDelta,
                AiOperation.CHAT_ROUTE);
        if (routed.toolCalls().isEmpty()) {
            // The model answered from what it already knows. Its text has been streamed as it
            // arrived, exactly like an ordinary turn - the caller is the one that knows this means
            // the answer did not come from the course.
            return text(routed);
        }

        List<LlmMessage> conversation = new ArrayList<>(messages);
        conversation.add(LlmMessage.toolRequest(routed.toolCalls(), routed.toolPlan()));

        ToolCall first = routed.toolCalls().get(0);
        ToolResult outcome = invoker.invoke(first);
        if (outcome.isSettled()) {
            // The tool answered the question by itself - a cache hit, or the confidence gate
            // refusing. Emitted as one fragment so the caller sees the same event sequence it sees
            // for a generated answer, and returned without a second provider call.
            onDelta.accept(outcome.finalAnswer());
            return outcome.finalAnswer();
        }
        conversation.add(LlmMessage.toolResult(first.id(), outcome.content()));

        // Anything the model asked for beyond the first is answered rather than run. A provider
        // message is required for every call in the assistant turn - leaving one unanswered is a
        // malformed conversation - so the refusal is the answer, and the model writes from the one
        // result set it did get.
        for (ToolCall extra : routed.toolCalls().subList(1, routed.toolCalls().size())) {
            conversation.add(LlmMessage.toolResult(extra.id(), SECOND_CALL_REFUSED));
        }

        return text(stream(new ChatRequest(model, conversation, true, null), onDelta,
                AiOperation.CHAT_STREAM));
    }

    private static final String SECOND_CALL_REFUSED =
            "Only one search is allowed per question. Answer from the result already returned.";

    // The shared streaming POST. exchange() gives us the live response stream (no buffering), so
    // we can read Cohere's server-sent events line by line and forward each token as it lands.
    private StreamResult stream(ChatRequest request, Consumer<String> onDelta, AiOperation operation) {
        StreamResult result;
        try {
            result = restClient.post()
                    .uri(chatUrl)
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
        if (result == null) {
            throw new ChatException("Cohere chat returned an empty response.");
        }
        usageRecorder.record(PROVIDER, model, operation, result.inputTokens(), result.outputTokens());
        return result;
    }

    // A stream that produced no text produced nothing the caller can deliver.
    private static String text(StreamResult result) {
        if (result.text().isBlank()) {
            throw new ChatException("Cohere chat returned an empty response.");
        }
        return result.text().trim();
    }

    // Reads the SSE body: each event is a "data: {json}" line. "content-delta" events carry the
    // tokens; the closing "message-end" event carries the billed token counts, which is the only
    // place a streamed call reports what it cost.
    //
    // Phase 26.3 added the tool events. `tool-call-start` brings the id and the name,
    // `tool-call-delta` brings the argument object a fragment at a time, and `tool-plan-delta`
    // carries the model's prose about what it intends to look up.
    //
    // **The tool plan is accumulated and never forwarded to onDelta.** It reads like an answer and
    // is not one - it is reasoning about what to search for, written before anything has been
    // searched - so showing it would put an uncited paragraph on a student's screen. It is kept
    // only because the provider wants the assistant message handed back whole. What reaches the UI
    // in that moment is 26.1's stage sentence instead.
    private StreamResult readStream(java.io.InputStream body, Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        StringBuilder plan = new StringBuilder();
        Map<Integer, PartialCall> calls = new TreeMap<>();
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
                JsonNode message = event.path("delta").path("message");
                switch (event.path("type").asText()) {
                    case "content-delta" -> {
                        JsonNode text = message.path("content").path("text");
                        if (!text.isMissingNode() && !text.asText().isEmpty()) {
                            full.append(text.asText());
                            onDelta.accept(text.asText());
                        }
                    }
                    case "tool-plan-delta" -> plan.append(message.path("tool_plan").asText(""));
                    case "tool-call-start" -> {
                        JsonNode call = message.path("tool_calls");
                        PartialCall partial = calls.computeIfAbsent(
                                event.path("index").asInt(0), index -> new PartialCall());
                        partial.id = call.path("id").asText(null);
                        partial.name = call.path("function").path("name").asText(null);
                        partial.arguments.append(call.path("function").path("arguments").asText(""));
                    }
                    case "tool-call-delta" -> {
                        PartialCall partial = calls.computeIfAbsent(
                                event.path("index").asInt(0), index -> new PartialCall());
                        partial.arguments.append(
                                message.path("tool_calls").path("function").path("arguments").asText(""));
                    }
                    case "message-end" -> {
                        JsonNode billed = event.path("delta").path("usage").path("billed_units");
                        inputTokens = billed.path("input_tokens").asInt(0);
                        outputTokens = billed.path("output_tokens").asInt(0);
                    }
                    default -> {
                        // Cohere emits lifecycle events we have no use for - message-start,
                        // content-start, content-end, tool-call-end. Ignoring them in a default
                        // branch rather than listing them keeps a new one from being a crash.
                    }
                }
            }
        } catch (IOException e) {
            throw new ChatException("Cohere chat stream read failed: " + e.getMessage(), e);
        }
        // A call with no name is the tail of an event whose start we never saw - dropped rather
        // than dispatched, because invoking a tool whose name is null is a NullPointerException
        // dressed up as a model decision.
        List<ToolCall> toolCalls = calls.values().stream()
                .filter(partial -> partial.name != null)
                .map(PartialCall::build)
                .toList();
        return new StreamResult(full.toString(), plan.toString(), toolCalls, inputTokens, outputTokens);
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
                               @JsonProperty("response_format") ResponseFormat responseFormat,
                               List<Tool> tools) {

        // Every call written before Phase 26.3, which offers no tools. Omitted from the JSON
        // rather than sent empty: a `tools: []` field is a different request from no field at all,
        // and the whole point of the tool-calling flag is that with it off the provider sees
        // byte-for-byte the request it saw before.
        ChatRequest(String model, List<LlmMessage> messages, boolean stream,
                    ResponseFormat responseFormat) {
            this(model, messages, stream, responseFormat, null);
        }
    }

    // A tool as Cohere's request wants it: a type discriminator wrapping the name, the description
    // the model actually reads, and the JSON Schema for its arguments.
    private record Tool(String type, Function function) {

        static Tool from(ToolSpec spec) {
            return new Tool("function", new Function(spec.name(), spec.description(), spec.parameters()));
        }

        record Function(String name, String description, Map<String, Object> parameters) { }
    }

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

    // A finished stream: whichever of the two things the model produced — text, or a request to
    // call a tool — plus what Cohere said it billed for. Never both: the model either answers or
    // asks, and `toolCalls` being empty is how the caller tells which happened.
    private record StreamResult(String text, String toolPlan, List<ToolCall> toolCalls,
                                int inputTokens, int outputTokens) { }

    // A tool call while it is still arriving. The id and the name land in one event and the
    // arguments accumulate across several, so this is a builder rather than a record: the JSON
    // argument object is only a document once the last fragment has been appended.
    private static final class PartialCall {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCall build() {
            return ToolCall.function(id, name, arguments.toString());
        }
    }
}
