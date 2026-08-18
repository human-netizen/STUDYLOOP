package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.StubAiConfig.StubRerankClient;
import com.studyloop.backend.security.JwtService;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 12 through the API rather than through the stage: the reranker's ordering is what a caller
// receives, and the confidence gate is reading its score rather than the cosine.
//
// RerankStageTest already covers the stage's own decisions in isolation, so what is left is the
// wiring, and the wiring is where the interesting failure lives. The gate's behaviour changes
// depending on whether *something else* — a component two layers away, behind a flag and an API key
// — produced a score. That is not a property either class can be asked about alone.
//
// The stub reranker scores a passage on how much of the question's vocabulary it contains. Crude
// against a real cross-encoder and faithful in the one respect this test needs: it reads the
// question and the passage together, so an off-topic question scores near zero no matter how
// confidently the vector half retrieved something for it.
//
// The annotations match SearchTest's exactly so both share one cached ApplicationContext — see
// StubAiConfig on why a second one would cost a Supabase connection.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class RerankPipelineTest {

    // Every content word of this one is in the lecture, so it is a genuine full-text hit —
    // plainto_tsquery ANDs its terms, which makes "shares a word or two" not a hit at all and is
    // why the pair of tests below had to be built around a question that really does match.
    private static final String LEXICAL_HIT = "How do subproblems overlap?";
    private static final String ON_TOPIC = "What is dynamic programming and when does it apply?";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private StubRerankClient reranker;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private String courseId;
    private String lectureId;

    @BeforeEach
    void createCourseAndEnableReranking() throws Exception {
        User owner = saveUser("owner");
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        lectureId = ingestPdf("dynamic-programming.pdf",
                "Dynamic programming solves problems by combining subproblem solutions.",
                "It applies when subproblems overlap and have optimal substructure.");
        ingestPdf("red-black-trees.pdf",
                "A red-black tree keeps its height balanced by recolouring nodes after insertion.");

        reranker.reset();
        reranker.configured = true;
    }

    @AfterEach
    void disableReranking() {
        // The bean is shared with every other class in this context, and it is off by default there
        // on purpose. Leaving it on would change the pipeline under tests written against the fused
        // one, in an order-dependent way that only shows up when the suite is run as a whole.
        reranker.reset();
    }

    @Test
    void theRerankersOrderIsWhatTheApiReturns() throws Exception {
        JsonNode result = search("dynamic programming subproblem");

        assertEquals(1, reranker.calls.get(), "retrieval should rerank once, not once per document");
        assertEquals(lectureId, result.get("documents").get(0).get("documentId").asText(),
                "the passage holding the question's words should lead the results");
    }

    // The headline behaviour change of Phase 12.2, and the reason the lexical escape hatch had to
    // go along with the cosine it was propping up. Under the Phase 5.3 rule this question is
    // answered no matter how poor the match, because its words are in the index and one lexical hit
    // waved anything through — which is exactly how an in-domain question the course cannot answer
    // got past the gate, measured at 0 refusals in 8 by the 11.1 harness.
    //
    // The reranker is told to find nothing relevant rather than being handed a fixture engineered
    // to produce that verdict. "Those are the words you asked for and this passage still does not
    // answer you" is the judgement a cross-encoder can make and a term index cannot, so a test of
    // how the gate reacts to it should say it outright.
    @Test
    void aLowRelevanceRefusesEvenThoughTheQuestionsWordsMatched() throws Exception {
        reranker.forcedRelevance = 0.0;

        JsonNode refusal = chat(LEXICAL_HIT);

        assertTrue(refusal.get("answer").asText().startsWith("I don't have that"),
                () -> "the gate should refuse on relevance, got: " + refusal.get("answer").asText());
        assertFalse(refusal.get("questionEventId").isNull(),
                "a refusal is still escalatable to the forum");
    }

    // The other half of the same measurement. A gate that refuses everything scores perfectly on
    // the test above, so the pair has to be read together.
    @Test
    void aQuestionTheCourseDoesAnswerStillGetsThrough() throws Exception {
        JsonNode answered = chat(ON_TOPIC);

        assertFalse(answered.get("answer").asText().startsWith("I don't have that"));
        assertFalse(answered.get("citations").isEmpty(), "a grounded answer cites what it used");
    }

    // The controlled half of the pair above: same question, same corpus, and the single difference
    // is that no relevance score exists. A rerank outage must put the gate back on the Phase 5.3
    // rule rather than take it down with the provider — and under that rule this question is
    // answered, because its words are in the index. Getting a refusal here would mean the gate had
    // been skipped rather than fallen back, and getting one there would mean the escape hatch was
    // still open; the two tests together are what distinguishes those.
    @Test
    void withNoRelevanceScoreTheOlderRuleAnswersTheSameQuestion() throws Exception {
        reranker.failing = true;

        JsonNode answered = chat(LEXICAL_HIT);

        assertFalse(answered.get("answer").asText().startsWith("I don't have that"),
                "with no relevance score the gate falls back to cosine-and-lexical, which answers this");
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

    private JsonNode chat(String question) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatBody(question, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Rerank " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Algorithms", "desc"))))
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

    private record ChatBody(String question, String conversationId) { }
}
