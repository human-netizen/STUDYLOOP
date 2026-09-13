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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 23.2 — a question that names a week, aimed at the material filed under it.
//
// **Built as the pair 28.2's test is built as, and for the same reason.** The positive assertion —
// a question about week 3 is answered from the week 3 lecture — passes on an implementation that
// does nothing at all, because the right lecture is what retrieval would have found anyway. What
// proves the narrowing happened is the *refusal*: a question the week 3 material cannot answer,
// asked of a course where another lecture answers it perfectly, must come back empty-handed.
//
// The other half of this class is the rule that keeps that from being dangerous. A filter the
// corpus cannot evidence is discarded, so "week 9" in a course with no week 9 is three words in a
// sentence — and on a corpus that has never been labelled at all, which is every corpus this
// project has ever measured, the stage is a regex and nothing else.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class TaxonomyRetrievalTest {

    // As in ScopedRetrievalTest: each question's distinctive vocabulary belongs to exactly one of
    // the two documents, so what is under test is the narrowing rather than the wording.
    private static final String TREAP_QUESTION = "What is a treap?";
    private static final String HASHING_QUESTION = "What is linear probing?";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private String courseId;
    private String treapsId;
    private String hashingId;

    // Filenames that say nothing, deliberately: the inference is tested on its own and what is
    // under test here is the filter, so the taxonomy is set through the endpoint rather than
    // arriving by accident through the name.
    @BeforeEach
    void ingestTwoLectures() throws Exception {
        User owner = saveUser();
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        treapsId = ingestPdf("scan_a.pdf",
                "A treap is a binary search tree whose nodes also carry a random heap priority.",
                "Rotations restore the heap order after an insertion into the treap.");
        hashingId = ingestPdf("scan_b.pdf",
                "Linear probing resolves a hash collision by scanning forward to the next free slot.",
                "Its performance degrades as the load factor of the table approaches one.");
    }

    // The whole of this sub-phase's risk in one assertion, and the mirror of 28.2's headline test:
    // if the narrowing is real, the week 3 material cannot answer a question only week 5 covers.
    @Test
    void aQuestionAboutAWeekIsAnsweredOnlyFromThatWeeksMaterial() throws Exception {
        label(hashingId, 3, "LECTURE");
        label(treapsId, 5, "LECTURE");

        String answer = ask("What is a treap, from week 3?");

        assertTrue(answer.startsWith("I don't have that"),
                () -> "week 3 does not cover treaps, so a question aimed at it must refuse: " + answer);
    }

    // The paired positive, without which the test above would pass on an implementation that
    // refuses everything.
    //
    // **Every word of this question that is not the routing phrase is a word in the chunk, and
    // that is a constraint the fixture imposes rather than a preference.** `lexical-or` is off by
    // default, so `plainto_tsquery` AND-joins the question's content words and a single word the
    // corpus does not contain empties the sparse half — "say" is not a Postgres stopword, and
    // asking "what did week 3 *say* about linear probing" refuses for that reason alone, with the
    // narrowing working perfectly. It is the same defect the residual-query rewrite exists to
    // avoid, arriving through a word the rewrite has no business removing.
    @Test
    void theSameWeeksOwnQuestionIsStillAnswered() throws Exception {
        label(hashingId, 3, "LECTURE");
        label(treapsId, 5, "LECTURE");

        JsonNode answer = chat("What is linear probing, in week 3?");

        assertTrue(answer.get("citations").size() > 0,
                () -> "the week the question named must still answer its own material: " + answer);
        assertEquals(hashingId, answer.get("citations").get(0).get("documentId").asText());
    }

    // **A filter the corpus cannot evidence is not a filter.** Nothing in this course is filed
    // under week 9, so narrowing to it would turn a question the corpus answers perfectly into a
    // refusal — the stage fails open instead, for the same reason the rerank stage does.
    @Test
    void aWeekTheCourseDoesNotHaveIsNotAFilter() throws Exception {
        label(hashingId, 3, "LECTURE");

        JsonNode answer = chat("What is a treap, from week 9?");

        assertTrue(answer.get("citations").size() > 0,
                () -> "an unevidenced week must leave the search alone, not empty it: " + answer);
        assertEquals(treapsId, answer.get("citations").get(0).get("documentId").asText());
    }

    // **The claim that lets this stage default to on.** Every corpus this project has measured —
    // the fourteen-chapter fixture the golden set is graded against included — carries no week and
    // no category, so the filter is never evidenced, no narrowing happens and the query is not
    // rewritten. The pipeline is the unfiltered one, which is why no published number can move.
    //
    // **Asserted as a pair, on one question text.** The first half alone would pass on an
    // implementation that never narrows anything; the second half is the same sentence after the
    // corpus has been labelled, which is the positive control. Comparing two *differently worded*
    // questions would not have shown this at all — the stub embedder keys a vector on its text, so
    // any rewording changes retrieval for reasons that have nothing to do with this stage.
    @Test
    void anUnlabelledCorpusIsNeverNarrowed() throws Exception {
        String question = "What did the week 3 lecture say about linear probing?";

        JsonNode unlabelled = chat(question);
        assertTrue(unlabelled.get("scopeNote").isNull(),
                "with nothing filed under week 3, those are three words in a sentence");

        label(hashingId, 3, "LECTURE");

        assertEquals("Week 3 · Lecture", chat(question).get("scopeNote").asText(),
                "and the same sentence does narrow once the course has a week 3 — so the "
                        + "assertion above is about the corpus rather than about the question");
    }

    // **The reader's own scope wins and is never intersected with an inferred one.** They picked
    // the treaps lecture; the question also says "week 3", which is the hashing lecture. Silently
    // taking the intersection would answer nothing while the chips on screen said otherwise.
    @Test
    void anExplicitScopeIsNotNarrowedFurther() throws Exception {
        label(hashingId, 3, "LECTURE");

        JsonNode answer = chat("What is a treap, from week 3?", List.of(treapsId));

        assertTrue(answer.get("citations").size() > 0,
                () -> "the documents the reader chose are the documents that get searched: " + answer);
        assertEquals(treapsId, answer.get("citations").get(0).get("documentId").asText());
        assertTrue(answer.get("scopeNote").isNull(),
                "a reader-chosen scope is not a taxonomy narrowing and must not be reported as one");
    }

    // A narrowing the reader cannot see is a stage that has silently changed their answer — the
    // failure this project has named in four other places.
    @Test
    void theAnswerSaysWhatItWasNarrowedTo() throws Exception {
        label(hashingId, 3, "LECTURE");

        assertEquals("Week 3 · Lecture",
                chat("What did the week 3 lecture say about linear probing?").get("scopeNote").asText());
    }

    // **A narrowed turn neither reads nor writes the semantic cache**, for the reason 28.2's
    // scoped turn does not: the entry is keyed on the course and the question's embedding, with no
    // room for which subset of the corpus produced it. The row count is the assertion because
    // nothing else would show it — the answer is correct either way, and wrong tomorrow.
    @Test
    void aNarrowedAnswerIsNeverCached() throws Exception {
        label(hashingId, 3, "LECTURE");

        chat("What did week 3 say about linear probing?");
        assertEquals(0, cachedEntries(), "a narrowed answer must not enter a course-wide cache");

        chat(HASHING_QUESTION);
        assertEquals(1, cachedEntries(), "the same question with no week in it is cacheable");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private void label(String documentId, Integer week, String category) throws Exception {
        mockMvc.perform(put("/api/v1/courses/" + courseId + "/documents/" + documentId + "/taxonomy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TaxonomyBody(week, category, List.of()))))
                .andExpect(status().isOk());
    }

    private List<String> citationIds(JsonNode answer) {
        return answer.get("citations").findValuesAsText("chunkId");
    }

    private String ask(String question) throws Exception {
        return chat(question).get("answer").asText();
    }

    private JsonNode chat(String question) throws Exception {
        return chat(question, List.of());
    }

    private JsonNode chat(String question, List<String> documentIds) throws Exception {
        String response = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChatBody(question, null, documentIds))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private int cachedEntries() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from chat_cache_entries where course_space_id = cast(? as uuid)",
                Integer.class, courseId);
        return count == null ? 0 : count;
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("taxonomy-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Taxonomy Tester");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Data structures", "desc"))))
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
        String documentId = objectMapper.readTree(body).get("id").asText();
        ingestionService.ingest(UUID.fromString(documentId));
        return documentId;
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

    private record ChatBody(String question, String conversationId, List<String> documentIds) { }

    private record TaxonomyBody(Integer week, String category, List<String> tags) { }
}
