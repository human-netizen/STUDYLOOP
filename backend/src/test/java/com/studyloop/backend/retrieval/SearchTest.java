package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.security.JwtService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Semantic search (Phase 9.3). The retrieval underneath is Phase 5's and already has its own
// tests; what is new here is the presentation, and every one of these tests is about that: hits
// gathered under the document they came from, a windowed extract with the matched words marked,
// and the corpus-but-not-a-file case rendered as what it is.
//
// The stub embedder maps different strings to near-orthogonal vectors, so the vector half returns
// candidates for any query at all. That is realistic — ANN search has no threshold, which is why
// the chat needs a confidence gate — and it means these tests assert what came back *about* a
// document rather than which documents came back.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class SearchTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private String courseId;
    private String lectureId;

    @BeforeEach
    void createCourseWithTwoLectures() throws Exception {
        User owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        lectureId = ingestPdf("dynamic-programming.pdf",
                "Dynamic programming solves problems by combining subproblem solutions.",
                "It applies when subproblems overlap and have optimal substructure.");
        ingestPdf("red-black-trees.pdf",
                "A red-black tree keeps its height balanced by recolouring nodes after insertion.");
    }

    @Test
    void aMatchingPassageComesBackUnderTheDocumentItCameFrom() throws Exception {
        JsonNode result = search("subproblem");

        JsonNode lecture = documentFor(result, lectureId);
        assertEquals("dynamic-programming.pdf", lecture.get("filename").asText());
        assertEquals("UPLOAD", lecture.get("source").asText());
        assertTrue(lecture.get("hitCount").asInt() >= 1);
        assertEquals(1, lecture.get("hits").get(0).get("pageNumber").asInt(),
                "the page is what makes the result openable");
    }

    // The marks are the point: they are why a reader can tell at a glance which passage is the
    // one they wanted, without reading all of them.
    @Test
    void theWordsTheQueryMatchedAreMarkedAndOtherPassagesAreNot() throws Exception {
        JsonNode result = search("subproblem");

        List<String> marked = markedWords(documentFor(result, lectureId).get("hits").get(0));
        assertFalse(marked.isEmpty(), "the passage that matched should say which words did");
        assertTrue(marked.stream().allMatch(word -> word.toLowerCase().startsWith("subproblem")),
                () -> "only the query's words should be marked, got: " + marked);

        // The other lecture is returned by the vector half but shares no vocabulary with the
        // query, so nothing in it should be lit up.
        JsonNode other = otherThan(result, lectureId);
        if (other != null) {
            assertTrue(markedWords(other.get("hits").get(0)).isEmpty(),
                    "a passage that only matched semantically has no word to point at");
        }
    }

    // A long document matches in more than one place. Five hits in one lecture is one result with
    // five passages, not five results competing with each other.
    @Test
    void severalHitsInOneDocumentAreOneResult() throws Exception {
        String longId = ingestLecture();

        JsonNode result = search("checkpoint");

        JsonNode document = documentFor(result, longId);
        assertEquals(2, document.get("hitCount").asInt(),
                "both chunks of the document contain the word");
        assertEquals(2, document.get("hits").size());
        assertEquals(result.get("hitCount").asInt(),
                sumOf(result, "hitCount"),
                "the grouping must not lose or duplicate a hit");
    }

    // An accepted forum answer is corpus like any other document, so it is searchable — but there
    // is no file behind it and no page to open, and the result has to say so rather than offer a
    // click that lands in an empty viewer.
    @Test
    void anAcceptedForumAnswerIsSearchableAndSaysItHasNoFile() throws Exception {
        String threadId = openThread("Which airline flies to Reykjavik?");
        String answerId = postAnswer(threadId, "Icelandair and Play both fly there from most of Europe.");
        accept(threadId, answerId);

        JsonNode result = search("Icelandair");

        JsonNode forum = sourcedFrom(result, "FORUM");
        assertNotNull(forum, "the accepted answer should be searchable");
        assertTrue(forum.get("filename").asText().startsWith("Forum ·"));
        assertTrue(forum.get("hits").get(0).get("pageNumber").isNull(),
                "forum text never came from a page");
        assertFalse(markedWords(forum.get("hits").get(0)).isEmpty(),
                "the answer's own words should be marked");
    }

    // The page calls this on every submit, including the one that clears the box. An empty result
    // is the right answer to an empty question — and it costs no embedding call.
    @Test
    void aBlankQueryReturnsNothingRatherThanEverything() throws Exception {
        JsonNode result = search("   ");

        assertEquals(0, result.get("hitCount").asInt());
        assertEquals(0, result.get("documents").size());
    }

    @Test
    void someoneOutsideTheCourseCannotSearchIt() throws Exception {
        String outsider = jwtService.generateAccessToken(saveUser("outsider"));

        mockMvc.perform(get("/api/v1/courses/" + courseId + "/search?q=subproblem")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isForbidden());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private JsonNode search(String query) throws Exception {
        String body = mockMvc.perform(get("/api/v1/courses/" + courseId + "/search")
                        .param("q", query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static JsonNode documentFor(JsonNode result, String documentId) {
        for (JsonNode document : result.get("documents")) {
            if (document.get("documentId").asText().equals(documentId)) {
                return document;
            }
        }
        throw new AssertionError("document " + documentId + " is missing from the results");
    }

    private static JsonNode otherThan(JsonNode result, String documentId) {
        for (JsonNode document : result.get("documents")) {
            if (!document.get("documentId").asText().equals(documentId)) {
                return document;
            }
        }
        return null;
    }

    private static JsonNode sourcedFrom(JsonNode result, String source) {
        for (JsonNode document : result.get("documents")) {
            if (document.get("source").asText().equals(source)) {
                return document;
            }
        }
        return null;
    }

    private static List<String> markedWords(JsonNode hit) {
        List<String> marked = new ArrayList<>();
        for (JsonNode part : hit.get("snippet")) {
            if (part.get("match").asBoolean()) {
                marked.add(part.get("text").asText());
            }
        }
        return marked;
    }

    private static int sumOf(JsonNode result, String field) {
        int total = 0;
        for (JsonNode document : result.get("documents")) {
            total += document.get(field).asInt();
        }
        return total;
    }

    private String openThread(String title) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses/" + courseId + "/forum/threads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ThreadBody(title, null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String postAnswer(String threadId, String text) throws Exception {
        String body = mockMvc.perform(post(threadPath(threadId) + "/answers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnswerBody(text))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("answers").get(0).get("id").asText();
    }

    private void accept(String threadId, String answerId) throws Exception {
        mockMvc.perform(post(threadPath(threadId) + "/answers/" + answerId + "/accept")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String threadPath(String threadId) {
        return "/api/v1/courses/" + courseId + "/forum/threads/" + threadId;
    }

    // A document long enough to split into two chunks (the window is 400 words, the step 340),
    // with the searched-for word once in each of them.
    private String ingestLecture() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int line = 0; line < 42; line++) {
            StringBuilder text = new StringBuilder();
            for (int column = 0; column < 12; column++) {
                int word = line * 12 + column;
                text.append(word == 5 || word == 450 ? "checkpoint" : "filler").append(' ');
            }
            lines.add(text.toString().trim());
        }
        return ingestPdf("long-lecture.pdf", lines.toArray(String[]::new));
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Search " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCourseRequest("Algorithms", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String ingestPdf(String filename, String... lines) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdfBytes(lines)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        ingestionService.ingest(UUID.fromString(id));
        return id;
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

    private record ThreadBody(String title, String body, String questionEventId) { }

    private record AnswerBody(String body) { }
}
