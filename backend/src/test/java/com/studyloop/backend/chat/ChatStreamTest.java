package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.document.StubAiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The SSE chat stream is the only endpoint that leaves the filter chain and comes back: when the
// emitter completes, Spring MVC dispatches the same request a second time as DispatcherType.ASYNC
// to finish it off. MockMvc never performs that second pass for an SseEmitter, so this test runs a
// real server on a real port and reads an actual socket.
//
// That matters more than it sounds. The bug this guards against delivered every event perfectly —
// meta, delta, done — and only then failed, leaving the chunked body unterminated and the socket
// dropped. Any test that asserted on the events alone would have been green while the browser
// showed "the assistant is unavailable": the client only learns of the failure at EOF.
//
// Real server means real commits, so the fixtures are torn down by hand rather than rolled back.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiConfig.class)
class ChatStreamTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String token;
    private UUID userId;
    private UUID courseId;

    @BeforeEach
    void createFixtures() throws Exception {
        String email = "sse-" + UUID.randomUUID() + "@example.com";
        JsonNode registered = postJson("/api/v1/auth/register", null, 201, """
                {"email":"%s","password":"Passw0rd!23","displayName":"SSE Probe"}
                """.formatted(email));
        userId = UUID.fromString(registered.get("id").asText());

        // Registration answers with the user, not a token — the credentials still have to be traded
        // for one.
        JsonNode session = postJson("/api/v1/auth/login", null, 200, """
                {"email":"%s","password":"Passw0rd!23"}
                """.formatted(email));
        token = session.get("accessToken").asText();

        JsonNode course = postJson("/api/v1/courses", token, 201, """
                {"name":"SSE Probe Course"}
                """);
        courseId = UUID.fromString(course.get("id").asText());
    }

    // Deleting the course cascades to its memberships, conversations and messages (see V2/V8), which
    // is what frees the user to be deleted — nothing else references it.
    @AfterEach
    void deleteFixtures() {
        if (courseId != null) {
            courseSpaceRepository.deleteById(courseId);
        }
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    // A course with no documents trips the confidence gate, so this exercises the full streaming
    // lifecycle — emitter opened, events written, emitter completed, request re-dispatched — without
    // needing an ingested document or a model call.
    @Test
    void theStreamFinishesCleanlyInsteadOfDroppingTheConnection() throws Exception {
        HttpResponse<String> response = ask("What is dynamic programming?");

        // Reaching here at all is the assertion that matters: a truncated chunked body surfaces as
        // an IOException out of send(), because the client is still waiting for the terminating
        // chunk when the socket closes.
        assertEquals(200, response.statusCode());

        String body = response.body();
        assertTrue(body.contains("event:meta"), () -> "expected a meta event, got: " + body);
        assertTrue(body.contains("event:delta"), () -> "expected a delta event, got: " + body);
        assertTrue(body.contains("event:done"), () -> "expected a done event, got: " + body);
    }

    // The same request twice: proves the async dispatch leaves nothing behind that breaks the next
    // stream on the same connection (the client reuses it by default).
    @Test
    void aSecondStreamOnTheSameConnectionAlsoFinishes() throws Exception {
        ask("First question?");
        HttpResponse<String> second = ask("Second question?");

        assertEquals(200, second.statusCode());
        assertTrue(second.body().contains("event:done"));
    }

    private HttpResponse<String> ask(String question) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/courses/" + courseId + "/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(new ChatBody(question))))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postJson(String path, String bearer, int expectedStatus, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), () -> path + " failed: " + response.body());
        return objectMapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private record ChatBody(String question) { }
}
