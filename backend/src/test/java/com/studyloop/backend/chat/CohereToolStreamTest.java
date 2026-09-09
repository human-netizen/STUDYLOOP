package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.config.ChatProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageRecorder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Phase 26.3 — **tool calling is a wire protocol, and this is the test of the wire.**
//
// A tool call does not arrive as an object. It arrives as four kinds of streamed event, with the
// id and the name in one of them and the argument document spread across however many fragments
// the provider felt like sending, and the only way to know this client reassembles them correctly
// is to feed it the bytes. Against the live provider that costs money and is not reproducible;
// against a socket serving a canned stream it costs nothing and fails loudly when Cohere's shapes
// move.
//
// It runs with no Spring context at all — a local HTTP server, the client, and a usage recorder
// that counts which operation each round was billed as.
class CohereToolStreamTest {

    private static final ToolSpec SEARCH = ToolSpec.oneStringArgument(
            "search_course_materials", "Search the course.", "query", "What to look for.");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private CohereChatClient client;

    // Every request body the client sent, in order — the assertions about *what was asked for*
    // read these, which is the half of a protocol a response-only test cannot see.
    private final List<String> requests = new CopyOnWriteArrayList<>();
    // What the next response should be, one per request.
    private final Deque<String> responses = new ArrayDeque<>();
    private final List<AiOperation> billed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/chat", this::respond);
        server.start();

        ChatProperties properties = new ChatProperties("cohere",
                new ChatProperties.Cohere("test-key", "command-r-08-2024"), 0.25, 0.32, null, true);
        AiUsageRecorder recorder = new AiUsageRecorder(null) {
            @Override
            public void record(String provider, String model, AiOperation fallbackOperation,
                               int inputTokens, int outputTokens) {
                billed.add(fallbackOperation);
            }
        };
        client = new CohereChatClient(properties, recorder,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/chat");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = responses.poll().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // The model answered without reaching for the tool. One round, one bill, and the invoker was
    // never called — which is the whole latency argument for 26.3 and also the shape of every turn
    // that gets stored as GENERAL.
    @Test
    void textOnlyNeverRunsTheTool() {
        responses.add(events(
                contentDelta("Binary search is "), contentDelta("O(log n)."), messageEnd(12, 8)));

        List<String> streamed = new ArrayList<>();
        String answer = client.streamWithTools(List.of(LlmMessage.user("How fast is binary search?")),
                List.of(SEARCH), call -> {
                    throw new AssertionError("the tool must not run when no call was streamed");
                }, streamed::add);

        assertEquals("Binary search is O(log n).", answer);
        assertEquals(List.of("Binary search is ", "O(log n)."), streamed);
        assertEquals(1, requests.size());
        assertEquals(List.of(AiOperation.CHAT_ROUTE), billed);
    }

    // The argument object arrives in pieces and has to be one document by the time the tool sees
    // it. Split here at a point that is invalid JSON on its own, because that is the failure a
    // "parse each fragment" implementation would pass every other test and still have.
    @Test
    void aToolCallIsReassembledFromItsFragments() {
        responses.add(events(
                toolPlanDelta("I should look this up in "), toolPlanDelta("the course materials."),
                toolCallStart(0, "call-abc", "search_course_materials"),
                toolCallDelta(0, "{\"query\":\"radix"), toolCallDelta(0, " sort running time\"}"),
                messageEnd(30, 12)));
        responses.add(events(contentDelta("Radix sort is O(d(n+k)) [1]."), messageEnd(200, 9)));

        List<ToolCall> invoked = new ArrayList<>();
        String answer = client.streamWithTools(List.of(LlmMessage.user("How fast is radix sort?")),
                List.of(SEARCH), call -> {
                    invoked.add(call);
                    return ToolResult.of("[1] (sorting.pdf, p.4) Radix sort runs in O(d(n+k)).");
                }, token -> { });

        assertEquals(1, invoked.size());
        assertEquals("call-abc", invoked.get(0).id());
        assertEquals("search_course_materials", invoked.get(0).name());
        assertEquals("{\"query\":\"radix sort running time\"}", invoked.get(0).arguments());
        assertEquals("Radix sort is O(d(n+k)) [1].", answer);
        assertEquals(List.of(AiOperation.CHAT_ROUTE, AiOperation.CHAT_STREAM), billed);
    }

    // **The cap is structural, and this is what "structural" means**: the second request carries no
    // tools, so the model has nothing to call and the loop cannot run a third round. A counter
    // could be got wrong; a request with no tools in it cannot.
    @Test
    void theSecondRoundIsSentWithoutTools() throws Exception {
        responses.add(events(toolCallStart(0, "call-1", "search_course_materials"),
                toolCallDelta(0, "{\"query\":\"quicksort\"}"), messageEnd(30, 12)));
        responses.add(events(contentDelta("Quicksort picks a random pivot [1]."), messageEnd(200, 9)));

        client.streamWithTools(List.of(LlmMessage.user("How does quicksort pick a pivot?")),
                List.of(SEARCH), call -> ToolResult.of("[1] (sorting.pdf) A random pivot."),
                token -> { });

        assertEquals(2, requests.size());
        assertTrue(objectMapper.readTree(requests.get(0)).has("tools"),
                "the routing round has to offer the tool");
        assertFalse(objectMapper.readTree(requests.get(1)).has("tools"),
                "the answering round must not, or the model could ask to search again");

        // And the conversation it answers from carries both halves of what happened: the assistant
        // turn that asked, and the tool turn that answered.
        JsonNode messages = objectMapper.readTree(requests.get(1)).get("messages");
        JsonNode assistant = messages.get(messages.size() - 2);
        JsonNode tool = messages.get(messages.size() - 1);
        assertEquals("assistant", assistant.get("role").asText());
        assertEquals("call-1", assistant.get("tool_calls").get(0).get("id").asText());
        assertEquals("tool", tool.get("role").asText());
        assertEquals("call-1", tool.get("tool_call_id").asText());
    }

    // Two calls in one round: the first runs, the rest are answered rather than run. A provider
    // message is required for every call in the assistant turn, so the refusal has to be sent —
    // leaving one unanswered is a malformed conversation, not a saved call.
    @Test
    void onlyTheFirstOfSeveralCallsIsRun() throws Exception {
        responses.add(events(
                toolCallStart(0, "call-1", "search_course_materials"),
                toolCallDelta(0, "{\"query\":\"merge sort\"}"),
                toolCallStart(1, "call-2", "search_course_materials"),
                toolCallDelta(1, "{\"query\":\"quicksort\"}"),
                messageEnd(30, 20)));
        responses.add(events(contentDelta("Merge sort splits the array [1]."), messageEnd(200, 9)));

        List<String> ran = new ArrayList<>();
        client.streamWithTools(List.of(LlmMessage.user("Compare merge sort and quicksort.")),
                List.of(SEARCH), call -> {
                    ran.add(call.id());
                    return ToolResult.of("[1] (sorting.pdf) Merge sort splits.");
                }, token -> { });

        assertEquals(List.of("call-1"), ran);
        JsonNode messages = objectMapper.readTree(requests.get(1)).get("messages");
        JsonNode refused = messages.get(messages.size() - 1);
        assertEquals("call-2", refused.get("tool_call_id").asText());
        assertTrue(refused.get("content").asText().contains("Only one search"));
    }

    // The tool settled the question by itself — a cache hit, or the confidence gate refusing. The
    // second provider call is the thing being saved, so the assertion is that there is no second
    // request at all, and that the text still reached the reader through onDelta like any answer.
    @Test
    void aSettledToolResultEndsTheTurnWithoutGenerating() {
        responses.add(events(toolCallStart(0, "call-1", "search_course_materials"),
                toolCallDelta(0, "{\"query\":\"treaps\"}"), messageEnd(30, 12)));

        List<String> streamed = new ArrayList<>();
        String answer = client.streamWithTools(List.of(LlmMessage.user("What is a treap?")),
                List.of(SEARCH), call -> ToolResult.settled("I don't have that in this course's materials."),
                streamed::add);

        assertEquals("I don't have that in this course's materials.", answer);
        assertEquals(List.of("I don't have that in this course's materials."), streamed);
        assertEquals(1, requests.size());
        assertEquals(List.of(AiOperation.CHAT_ROUTE), billed);
    }

    // The tool plan is read off the stream and handed back to the provider, and never to the
    // reader. It is the model reasoning about what to look up, written before anything has been
    // looked up: on screen it would be an uncited paragraph that reads like an answer.
    @Test
    void theToolPlanIsCarriedButNeverStreamed() throws Exception {
        responses.add(events(
                toolPlanDelta("The student is asking about sorting, so I will "),
                toolPlanDelta("search the course materials."),
                toolCallStart(0, "call-1", "search_course_materials"),
                toolCallDelta(0, "{\"query\":\"sorting\"}"), messageEnd(30, 12)));
        responses.add(events(contentDelta("Sorting takes n log n [1]."), messageEnd(200, 9)));

        List<String> streamed = new ArrayList<>();
        client.streamWithTools(List.of(LlmMessage.user("Tell me about sorting.")), List.of(SEARCH),
                call -> ToolResult.of("[1] (sorting.pdf) Comparison sorting is n log n."), streamed::add);

        assertEquals(List.of("Sorting takes n log n [1]."), streamed);
        JsonNode messages = objectMapper.readTree(requests.get(1)).get("messages");
        JsonNode assistant = messages.get(messages.size() - 2);
        assertEquals("The student is asking about sorting, so I will search the course materials.",
                assistant.get("tool_plan").asText());
        assertNull(assistant.get("content"), "an assistant turn that asked for a tool said nothing");
    }

    // ------------------------------------------------------------------------------------------
    // Canned events, in Cohere v2's shapes.
    // ------------------------------------------------------------------------------------------

    private static String events(String... lines) {
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            body.append("data: ").append(line).append("\n\n");
        }
        return body.toString();
    }

    private static String contentDelta(String text) {
        return "{\"type\":\"content-delta\",\"delta\":{\"message\":{\"content\":{\"text\":\"%s\"}}}}"
                .formatted(text);
    }

    private static String toolPlanDelta(String text) {
        return "{\"type\":\"tool-plan-delta\",\"delta\":{\"message\":{\"tool_plan\":\"%s\"}}}"
                .formatted(text);
    }

    private static String toolCallStart(int index, String id, String name) {
        return ("{\"type\":\"tool-call-start\",\"index\":%d,\"delta\":{\"message\":{\"tool_calls\":"
                + "{\"id\":\"%s\",\"type\":\"function\",\"function\":{\"name\":\"%s\","
                + "\"arguments\":\"\"}}}}}").formatted(index, id, name);
    }

    private static String toolCallDelta(int index, String arguments) {
        return ("{\"type\":\"tool-call-delta\",\"index\":%d,\"delta\":{\"message\":{\"tool_calls\":"
                + "{\"function\":{\"arguments\":\"%s\"}}}}}")
                .formatted(index, arguments.replace("\"", "\\\""));
    }

    private static String messageEnd(int inputTokens, int outputTokens) {
        return ("{\"type\":\"message-end\",\"delta\":{\"usage\":{\"billed_units\":"
                + "{\"input_tokens\":%d,\"output_tokens\":%d}}}}")
                .formatted(inputTokens, outputTokens);
    }
}
