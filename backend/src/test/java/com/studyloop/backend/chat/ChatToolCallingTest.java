package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.StubAiConfig.RecordingChatClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Phase 26.3 — the turn where the model decides whether the course materials are needed at all,
// behind `studyloop.chat.tool-calling`.
//
// **The flag-off case is not tested here: it is tested by the rest of the suite.** Every other
// chat test in this package runs with the flag at its default, so "off reproduces today's
// pipeline" is asserted by SemanticCacheTest, RecurringQuestionTest, BanglaAnswerTest,
// ConfidenceGateTest and GeneralKnowledgeTest continuing to pass. A copy of one of them with the
// flag off would prove less and cost another ApplicationContext.
//
// What is asserted here is the product invariant the "Deliberately not building" table objected
// this feature would break: **every answer is either cited or explicitly labelled as uncited.** A
// turn that searched comes back with citations and is stored as ASSISTANT; a turn that did not is
// stored as GENERAL, which is Phase 20.2's already-labelled role, and carries no citations at all.
//
// Real server on a real port, for ChatStreamTest's reason: MockMvc never performs the second ASYNC
// dispatch an SseEmitter needs, so a stream that fails at completion looks green through it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "studyloop.chat.tool-calling=true")
@Import(StubAiConfig.class)
class ChatToolCallingTest {

    // Shares vocabulary with the ingested PDF, so the lexical half of retrieval carries the
    // confidence gate. The stub's embeddings are random per string, so the semantic half is noise
    // here — which keeps these tests about routing rather than about retrieval quality.
    private static final String GROUNDED_QUESTION = "What is dynamic programming?";
    private static final String GENERAL_QUESTION = "What is the time complexity of binary search?";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    @Autowired
    private RecordingChatClient chatClient;

    @Autowired
    private SemanticCacheService semanticCache;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String token;
    private UUID userId;
    private UUID courseId;

    @BeforeEach
    void createFixtures() throws Exception {
        String email = "tools-" + UUID.randomUUID() + "@example.com";
        JsonNode registered = postJson("/api/v1/auth/register", null, 201, """
                {"email":"%s","password":"Passw0rd!23","displayName":"Tool Probe"}
                """.formatted(email));
        userId = UUID.fromString(registered.get("id").asText());

        token = postJson("/api/v1/auth/login", null, 200, """
                {"email":"%s","password":"Passw0rd!23"}
                """.formatted(email)).get("accessToken").asText();

        courseId = UUID.fromString(postJson("/api/v1/courses", token, 201, """
                {"name":"Tool Probe Course"}
                """).get("id").asText());

        ingest("Dynamic programming solves problems by combining subproblem solutions.",
                "It applies when subproblems overlap and have optimal substructure.");

        // Ingestion ends by summarizing the document, which is itself a provider call. Reset so
        // every count below is chat and nothing else.
        chatClient.reset();
    }

    @AfterEach
    void deleteFixtures() {
        if (courseId != null) {
            semanticCache.invalidate(courseId);
            courseSpaceRepository.deleteById(courseId);
        }
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    // The model asked to search. Two provider calls — the router and the answer — and the turn
    // comes back with the citations retrieval found.
    @Test
    void aSearchedTurnIsCitedAndStoredAsAnAssistantTurn() throws Exception {
        chatClient.nextToolQuery = "dynamic programming";

        String stream = ask(GROUNDED_QUESTION);

        assertEquals(1, chatClient.toolInvocations.get(), "the model asked for the tool once");
        assertEquals(2, chatClient.calls.get(), "the routing call and the answering call");
        JsonNode meta = event(stream, "meta");
        assertFalse(meta.get("citations").isEmpty(), "a searched turn cites what it found");
        assertEquals(List.of("USER", "ASSISTANT"), rolesInThread());
    }

    // The model answered from what it knows. One provider call, no citations, and the turn is
    // stored as GENERAL — the role that renders with "answered from general knowledge, not from
    // the course materials" and keeps saying so when the thread is replayed.
    @Test
    void anUnsearchedTurnIsLabelledGeneralAndCitesNothing() throws Exception {
        chatClient.nextToolQuery = null;

        String stream = ask(GENERAL_QUESTION);

        assertEquals(0, chatClient.toolInvocations.get());
        assertEquals(1, chatClient.calls.get(), "no search means no second call");
        assertTrue(event(stream, "meta").get("citations").isEmpty());
        assertTrue(stream.contains("event:delta"), "the answer still streams");
        assertEquals(List.of("USER", "GENERAL"), rolesInThread());
    }

    // **Nothing ungrounded reaches the cache.** The cache exists to serve a question the course
    // materials answered; an answer that never touched them must never later be handed to somebody
    // who asked the course something.
    @Test
    void anUnsearchedAnswerIsNotCached() throws Exception {
        chatClient.nextToolQuery = null;

        ask(GENERAL_QUESTION);

        assertEquals(0, cachedEntries(), "an answer with no sources is not a cacheable answer");
    }

    // The cache probe moved inside the tool (26.2/26.3), so a hit is found only once the model has
    // decided it wants the materials — and then it ends the turn. The saving is the generation
    // call: one provider call for the whole turn instead of two.
    @Test
    void aCacheHitInsideTheToolEndsTheTurnWithoutGenerating() throws Exception {
        chatClient.nextToolQuery = "dynamic programming";
        ask(GROUNDED_QUESTION);
        assertEquals(2, chatClient.calls.get());
        assertEquals(1, cachedEntries(), "the first answer was grounded, so it was cached");

        chatClient.reset();
        chatClient.nextToolQuery = "dynamic programming";
        String stream = ask(GROUNDED_QUESTION);

        assertEquals(1, chatClient.calls.get(),
                "the routing call happened; the answer came from the cache instead of the model");
        assertEquals(1, chatClient.toolInvocations.get());
        assertTrue(stream.contains("event:delta"), "the cached answer is still delivered");
    }

    // The wire order the client depends on, on the path where the citations do not exist until
    // after the model has spoken once: stage events first, then meta, then the answer.
    @Test
    void theEventOrderIsUnchangedOnTheToolPath() throws Exception {
        chatClient.nextToolQuery = "dynamic programming";

        String stream = ask(GROUNDED_QUESTION);

        int firstStage = stream.indexOf("event:stage");
        int meta = stream.indexOf("event:meta");
        int firstDelta = stream.indexOf("event:delta");
        assertTrue(firstStage >= 0 && firstStage < meta,
                () -> "a stage should precede meta, got: " + stream);
        assertTrue(meta < firstDelta, () -> "meta precedes the answer, got: " + stream);
        assertTrue(stream.lastIndexOf("event:stage") < firstDelta,
                () -> "stages stop once text is arriving, got: " + stream);
    }

    // ------------------------------------------------------------------------------------------

    private List<String> rolesInThread() {
        return jdbc.queryForList("""
                select m.role from chat_messages m
                join chat_conversations c on c.id = m.conversation_id
                where c.course_space_id = ?
                order by m.created_at
                """, String.class, courseId);
    }

    private int cachedEntries() {
        Integer count = jdbc.queryForObject(
                "select count(*) from chat_cache_entries where course_space_id = ?", Integer.class, courseId);
        return count == null ? 0 : count;
    }

    private String ask(String question) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/courses/" + courseId + "/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(new QuestionBody(question, null))))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    // The JSON of the first event with this name.
    private JsonNode event(String stream, String name) throws IOException {
        for (String block : stream.split("\n\n")) {
            if (block.contains("event:" + name)) {
                for (String line : block.split("\n")) {
                    if (line.startsWith("data:")) {
                        return objectMapper.readTree(line.substring("data:".length()));
                    }
                }
            }
        }
        throw new AssertionError("no " + name + " event in: " + stream);
    }

    private JsonNode postJson(String path, String bearer, int expected, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response.body());
        return objectMapper.readTree(response.body());
    }

    private void ingest(String... lines) throws Exception {
        String boundary = "----probe" + UUID.randomUUID();
        byte[] pdf = pdfBytes(lines);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                    + "filename=\"material.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes());
        body.write(pdf);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/courses/" + courseId + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), response.body());
        UUID documentId = UUID.fromString(objectMapper.readTree(response.body()).get("id").asText());
        waitUntilReady(documentId);
        waitUntilSummarized(documentId);
    }

    // **Waited for rather than driven, and the difference cost an afternoon once already.** This
    // class runs against a real server, so the upload commits and `DocumentIngestionListener`
    // picks the document up on the ingestion executor. Calling `DocumentIngestionService.ingest`
    // from the test thread as well does not make that happen sooner - it makes it happen twice,
    // concurrently, and the document can end up with no searchable chunks. Every question then
    // trips the confidence gate and the tests fail somewhere else entirely.
    private void waitUntilReady(UUID documentId) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/v1/courses/" + courseId
                                    + "/documents/" + documentId))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            String status = objectMapper.readTree(response.body()).get("status").asText();
            if ("READY".equals(status)) {
                return;
            }
            assertFalse("FAILED".equals(status), () -> "the fixture failed to ingest: " + response.body());
            Thread.sleep(500);
        }
        throw new AssertionError("the fixture never finished ingesting");
    }

    // **READY is not the end of an ingest, and treating it as one is a race this class lost.**
    // DocumentIngestionService marks the document READY and *then* invalidates the cache, sweeps
    // the forum and summarizes - and the summary is a provider call. A fixture that stops waiting
    // at READY and immediately resets the call counter therefore sometimes counts the summary as
    // the turn's first chat call, and the assertion "a grounded turn is two calls" fails with
    // three. It passes or fails on how quickly the summarizer's thread gets scheduled, which is
    // why it survived one full run and failed the next.
    //
    // Waiting on the artifact rather than on a sleep: the summary endpoint returns null until
    // generation has finished, so this is the same "poll the thing you actually need" shape
    // waitUntilReady uses.
    private void waitUntilSummarized(UUID documentId) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/v1/courses/" + courseId
                                    + "/documents/" + documentId + "/summary"))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode summary = objectMapper.readTree(response.body()).get("summary");
            if (summary != null && !summary.isNull()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the fixture was never summarized");
    }

    private byte[] pdfBytes(String... lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                for (String line : lines) {
                    content.showText(line);
                    content.newLineAtOffset(0, -16);
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private record QuestionBody(String question, String conversationId) { }
}
