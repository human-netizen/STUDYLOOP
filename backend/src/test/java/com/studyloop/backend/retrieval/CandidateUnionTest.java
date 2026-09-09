package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.EmbeddingClient;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.VectorSupport;
import com.studyloop.backend.retrieval.ChunkSearchRepository.Candidates;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 26.2 — the union query returns the same three lists the three queries returned.
//
// **This is the test that makes the change believable, and without it the change is not.** What
// the union could break is not "no results" — that fails loudly and every other retrieval test
// would catch it. What it could break is a list's *internal ordering*, and Reciprocal Rank Fusion
// reads position within a list: a candidate that moves from rank 3 to rank 4 changes its RRF
// contribution, changes the fused order, changes what the reranker is given, and moves every
// Recall@6, MRR and nDCG figure this project has published — silently, with nothing thrown and no
// other test failing.
//
// So the assertion is element-for-element and in order, not set equality.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class CandidateUnionTest {

    private static final int CANDIDATES = 20;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private ChunkSearchRepository searchRepository;

    @Autowired
    private EmbeddingClient embeddingClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private UUID actorId;
    private UUID courseId;

    @BeforeEach
    void ingestACorpus() throws Exception {
        User owner = saveUser();
        actorId = owner.getId();
        token = jwtService.generateAccessToken(owner);
        courseId = UUID.fromString(createCourse());

        // Three documents so each ranked list has more than one thing in it and an ordering worth
        // preserving. One query has to reach several of them, which is why they share vocabulary.
        ingestPdf("sorting.pdf",
                "Merge sort splits the array in half and merges the sorted halves.",
                "Quicksort partitions the array around a randomly chosen pivot element.");
        ingestPdf("lower-bounds.pdf",
                "No comparison sorting algorithm can beat n log n comparisons in the worst case.",
                "The decision tree for sorting has n factorial leaves, so its height is n log n.");
        ingestPdf("counting.pdf",
                "Radix sort sorts integers digit by digit using a stable counting sort.",
                "Counting sort runs in linear time when the key range is small.");
    }

    @Test
    void theUnionReturnsTheSameThreeListsInTheSameOrder() {
        for (String query : List.of("sorting comparisons", "pivot", "radix digit counting")) {
            float[] vector = embeddingClient.embedQuery(query);
            String literal = VectorSupport.toLiteral(vector);

            List<ChunkHit> separateVector =
                    searchRepository.vectorSearch(courseId, actorId, literal, CANDIDATES);
            List<ChunkHit> separateText =
                    searchRepository.fullTextSearch(courseId, actorId, query, CANDIDATES, false);
            List<ChunkHit> separateVisual =
                    searchRepository.visualSearch(courseId, actorId, literal, CANDIDATES);

            Candidates union = searchRepository.candidateSearch(
                    courseId, actorId, literal, query, CANDIDATES, false, true);

            assertIdentical(query + " / dense", separateVector, union.vector());
            assertIdentical(query + " / lexical", separateText, union.text());
            assertIdentical(query + " / visual", separateVisual, union.visual());
        }
    }

    // The `lexicalOr` switch is a pipeline decision (19.2) that changes what the sparse half
    // returns, and the union had to learn it too. A run with the flag on and the flag off must
    // still be a run of the same two queries.
    @Test
    void theUnionHonoursTheOrFormOfTheLexicalQuery() {
        String query = "sorting comparisons pivot";
        String literal = VectorSupport.toLiteral(embeddingClient.embedQuery(query));

        for (boolean anyTerm : List.of(false, true)) {
            List<ChunkHit> separate =
                    searchRepository.fullTextSearch(courseId, actorId, query, CANDIDATES, anyTerm);
            Candidates union = searchRepository.candidateSearch(
                    courseId, actorId, literal, query, CANDIDATES, anyTerm, false);
            assertIdentical("anyTerm=" + anyTerm, separate, union.text());
        }
    }

    // A course with no embedding provider degrades to full-text alone, which the union expresses
    // by leaving the dense branch out of the statement rather than by running it and discarding
    // the rows.
    @Test
    void aMissingVectorLeavesTheDenseListEmptyRatherThanFailing() {
        Candidates union = searchRepository.candidateSearch(
                courseId, actorId, null, "sorting", CANDIDATES, false, true);

        assertEquals(List.of(), union.vector());
        assertEquals(List.of(), union.visual(), "no vector means nothing to search page images with");
        assertFalse(union.text().isEmpty(), "the lexical half still runs");
    }

    // The visual branch is switched off at the caller, so it is not in the statement at all — the
    // stage flag now decides whether a branch exists rather than whether a second query runs.
    @Test
    void theVisualBranchIsAbsentWhenTheStageIsOff() {
        String literal = VectorSupport.toLiteral(embeddingClient.embedQuery("sorting"));
        Candidates union = searchRepository.candidateSearch(
                courseId, actorId, literal, "sorting", CANDIDATES, false, false);

        assertEquals(List.of(), union.visual());
        assertFalse(union.vector().isEmpty(), "the other two branches are unaffected");
    }

    private static void assertIdentical(String what, List<ChunkHit> expected, List<ChunkHit> actual) {
        assertEquals(expected.size(), actual.size(), what + ": the lists are different lengths");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).id(), actual.get(i).id(),
                    what + ": rank " + i + " is a different chunk, which moves every RRF score");
            assertEquals(expected.get(i).content(), actual.get(i).content(), what + ": rank " + i);
            // **Compared to a tolerance, and the tolerance is a finding rather than a
            // convenience.** The two forms of the query return cosine similarities that differ in
            // the last few units in the last place - 0.030398879352412367 against
            // 0.0303988793524124 on the fixture below - because the planner evaluates the same
            // expression differently either side of a subquery boundary. It cannot matter: the
            // number is read by the confidence gate against a threshold of 0.25 or 0.32, and by
            // nothing else. What would matter is the *ranking* moving, and that is asserted above
            // exactly.
            Double expectedScore = expected.get(i).cosineSimilarity();
            Double actualScore = actual.get(i).cosineSimilarity();
            if (expectedScore == null || actualScore == null) {
                // The lexical list has no similarity to report, and null is how it says so. That
                // the union preserves the *absence* matters: the confidence gate reads null as
                // "no semantic signal" rather than as a zero.
                assertEquals(expectedScore, actualScore, what + ": rank " + i);
            } else {
                assertEquals(expectedScore, actualScore, 1e-12,
                        what + ": rank " + i + " has a different similarity");
            }
        }
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("union-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Union Probe");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Sorting", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void ingestPdf(String filename, String... lines) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdfBytes(lines)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        assertNotNull(id);
        ingestionService.ingest(UUID.fromString(id));
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
}
