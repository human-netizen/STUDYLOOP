package com.studyloop.backend.guide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.StubAiConfig.RecordingChatClient;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 22 — a study guide, and the four claims it makes.
//
// Grounding is mandatory rather than a flag; a section the corpus cannot support is a *gap* and
// not an invention; the citations belong to the section that retrieved them; and a topic the
// course does not cover is refused before anything is written. Every test here is one of those.
//
// **The generation is driven synchronously.** In production it starts from an AFTER_COMMIT event
// on the guide executor; inside a @Transactional test nothing ever commits, so the listener would
// never fire and every assertion would be about an empty row. Calling the runner directly is what
// DocumentIngestionTest and VideoGenerationTest both do, and it tests the thing worth testing.
//
// **No `properties` and no second `@Import`.** Either would give this class its own cached
// ApplicationContext and its own Hikari pool, against a session pooler that allows fifteen clients
// in total — the failure VideoGenerationTest documents. The stubs come from StubAiConfig, shared
// with every other integration test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class StudyGuideGenerationTest {

    // With no reranker configured the confidence gate falls back to Phase 5.3's rule — weak cosine
    // AND no lexical hit — and the stub embedder's cosines are deterministic noise. So the lexical
    // half is what carries a topic past the gate, which makes coverage precisely controllable
    // here: a heading built from words on the page is covered, and one built from words that are
    // not is a gap. That is exactly the distinction under test.
    private static final String COVERED = "How does dynamic programming combine subproblem solutions?";
    private static final String UNCOVERED = "Which airline flies to Reykjavik?";

    // The second heading names nothing on the ingested page, so it retrieves nothing the gate will
    // pass — which is what a real study guide's uncovered section looks like: a topic the subject
    // needs and this course never taught.
    private static final String COVERED_HEADING = "Combining subproblem solutions";
    private static final String GAP_HEADING = "Reykjavik airline schedules";

    private static final String OUTLINE_JSON = """
            {"sections":["%s","%s"]}
            """.formatted(COVERED_HEADING, GAP_HEADING);

    private static final String SECTION_JSON = """
            {"body":"Each subproblem is solved once and its answer reused [1].",
             "diagram":"flowchart TD\\n  A[Subproblem] --> B[Stored answer]"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RecordingChatClient chatClient;

    @Autowired
    private StudyGuideRunner runner;

    @Autowired
    private StudyGuideReconciler reconciler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String ownerToken;
    private User owner;
    private String courseId;

    @BeforeEach
    void createCourseWithMaterial() throws Exception {
        owner = saveUser("owner");
        ownerToken = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        ingestPdf("Dynamic programming solves problems by combining subproblem solutions, "
                + "storing each subproblem answer once so it is never recomputed.");
        chatClient.reset();
    }

    // The whole path: a topic the corpus covers becomes a guide whose written sections carry the
    // passages they were written from, numbered from one within the section.
    @Test
    void aCoveredTopicBecomesAGuideWhoseSectionsCiteTheirOwnSources() throws Exception {
        scriptTheModel();

        JsonNode guide = writeNow(COVERED);

        assertEquals("READY", guide.get("status").asText());
        assertEquals(2, guide.get("sectionsPlanned").asInt());
        assertEquals(1, guide.get("sectionsWritten").asInt());
        assertEquals(2, guide.get("sections").size());

        JsonNode first = guide.get("sections").get(0);
        assertEquals(1, first.get("position").asInt());
        assertEquals(COVERED_HEADING, first.get("heading").asText());
        assertTrue(first.get("covered").asBoolean());
        assertTrue(first.get("body").asText().contains("[1]"), first.get("body").asText());
        assertTrue(first.get("citations").size() > 0, "a written section cites what it read");
        assertEquals(1, first.get("citations").get(0).get("index").asInt(),
                "numbering restarts inside each section — that is what per-section retrieval "
                        + "looks like from the outside");
        assertFalse(first.get("citations").get(0).get("documentId").isNull());
    }

    // **The claim the whole phase rests on.** ZenLearn generates a document with no holes in it,
    // which is a statement about the model rather than about the course. A section this course
    // cannot support is listed, named, and left empty — and the model is never asked to write it.
    @Test
    void aSectionTheMaterialsDoNotCoverIsAGapRatherThanAnInvention() throws Exception {
        scriptTheModel();

        JsonNode guide = writeNow(COVERED);

        JsonNode gap = guide.get("sections").get(1);
        assertEquals(GAP_HEADING, gap.get("heading").asText());
        assertFalse(gap.get("covered").asBoolean());
        assertTrue(gap.get("body").isNull(), "nothing is written under a gap");
        assertTrue(gap.get("diagram").isNull());
        assertEquals(0, gap.get("citations").size());

        // And it cost nothing: one outline call plus one section call, not two section calls.
        assertEquals(2, chatClient.calls.get(), "a gap is one embedding and no completion");
        assertEquals(2, guide.get("modelCalls").asInt());
    }

    // The refusal, and where it happens. The most expensive user action in the product has the
    // cheapest possible failure: no outline is planned for a topic the course has nothing on.
    @Test
    void anUncoveredTopicIsRefusedBeforeTheOutlineCallIsMade() throws Exception {
        scriptTheModel();

        JsonNode guide = writeNow(UNCOVERED);

        assertEquals("REFUSED", guide.get("status").asText());
        assertEquals(0, guide.get("sectionsPlanned").asInt());
        assertEquals(0, guide.get("sections").size());
        assertEquals(0, chatClient.calls.get(), "not one completion was paid for");
        assertTrue(guide.get("error").isNull(),
                "REFUSED is not a failure and carries no error text — the status is the message");
    }

    // Two calls, two different asks. AiOperation keeps them apart so the dashboard can answer
    // "how many guides" separately from "how many sections the corpus could support"; what this
    // pins is the thing that makes those numbers mean anything — that they really are two distinct
    // prompts rather than one reused.
    @Test
    void theOutlineAndTheSectionAreTwoDifferentPrompts() throws Exception {
        scriptTheModel();

        writeNow(COVERED);

        assertEquals(2, chatClient.prompts.size());
        assertTrue(chatClient.prompts.get(0).contains("study guide planner"), chatClient.prompts.get(0));
        assertTrue(chatClient.prompts.get(1).contains("study guide writer"), chatClient.prompts.get(1));
    }

    // The outline prompt forbids the four headings that would break per-section retrieval. A
    // heading is also a search query here, so "Introduction" retrieves noise and writes a section
    // from it — the failure this rule exists to prevent, stated where the model can read it.
    @Test
    void theOutlinePromptRefusesHeadingsThatWouldNotSurviveBeingSearchedFor() throws Exception {
        scriptTheModel();

        writeNow(COVERED);

        String prompt = chatClient.prompts.get(0);
        assertTrue(prompt.contains("also a search query"), prompt);
        assertTrue(prompt.contains("\"Introduction\""), prompt);
    }

    // A provider failure on one section costs that section, not the guide. Six completions is six
    // chances to lose one, and throwing away five good sections over the sixth would be paying for
    // work and then discarding it.
    @Test
    void aSectionTheProviderCouldNotWriteBecomesAGapRatherThanFailingTheGuide() throws Exception {
        chatClient.jsonQueue.add(OUTLINE_JSON);
        // Every call after the outline returns something that is not JSON at all, twice over —
        // which is what the parser retry is for, and what it gives up on.
        chatClient.nextJson = "I'm afraid I can't do that.";

        JsonNode guide = writeNow(COVERED);

        assertEquals("READY", guide.get("status").asText(), "the guide survives");
        assertEquals(0, guide.get("sectionsWritten").asInt());
        assertFalse(guide.get("sections").get(0).get("covered").asBoolean());
        assertEquals(1, guide.get("modelCalls").asInt(),
                "the outline is counted; a section that produced nothing is not");
    }

    // 22.2's diagram guard. Mermaid's `click` directive binds a callback or a URL to a node, which
    // is a script primitive inside a picture generated from model output — and model output is
    // partly the student's own input. It is dropped here, and the renderer sandboxes it again.
    @Test
    void aDiagramThatTriesToBindAClickHandlerIsDropped() throws Exception {
        chatClient.jsonQueue.add(OUTLINE_JSON);
        chatClient.jsonQueue.add("""
                {"body":"Solved once, reused after [1].",
                 "diagram":"flowchart TD\\n  A[Step] --> B[Step]\\n  click A \\"https://example.com\\""}
                """);

        JsonNode guide = writeNow(COVERED);

        JsonNode written = guide.get("sections").get(0);
        assertTrue(written.get("covered").asBoolean(), "the prose is kept");
        assertTrue(written.get("diagram").isNull(), "and the diagram is not");
    }

    // A diagram the model did write, kept as the text it is. Mermaid rather than a generated
    // image: this is a claim derived from the sources, so it can be read, printed and checked.
    @Test
    void aCleanDiagramIsStoredAsItsOwnSource() throws Exception {
        scriptTheModel();

        JsonNode guide = writeNow(COVERED);

        String diagram = guide.get("sections").get(0).get("diagram").asText();
        assertTrue(diagram.startsWith("flowchart TD"), diagram);
    }

    // The startup sweep. An in-process queue loses its work on every restart, and the only
    // unacceptable version of that is a guide that says "writing section 3 of 6" forever.
    @Test
    void aRestartFailsAGuideTheLastProcessLeftMidFlight() throws Exception {
        UUID guideId = insertGuide(StudyGuideStatus.WRITING);

        reconciler.failInterruptedGuides();

        JsonNode guide = fetch(guideId.toString(), ownerToken);
        assertEquals("FAILED", guide.get("status").asText());
        assertTrue(guide.get("error").asText().contains("interrupted by a server restart"),
                () -> guide.get("error").asText());
    }

    // A guide can be grounded on the requester's own private notes, so it inherits their
    // visibility rather than the course's — Phase 21's rule, applied to a second artifact. A
    // classmate gets the 404 they would get for a guide that does not exist.
    @Test
    void anotherMembersGuideIsNotReachable() throws Exception {
        scriptTheModel();
        JsonNode mine = writeNow(COVERED);

        User classmate = saveUser("classmate");
        addMember(classmate);
        String theirToken = jwtService.generateAccessToken(classmate);

        mockMvc.perform(get(guides() + "/" + mine.get("id").asText())
                        .header("Authorization", "Bearer " + theirToken))
                .andExpect(status().isNotFound());

        String library = mockMvc.perform(get(guides())
                        .header("Authorization", "Bearer " + theirToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, objectMapper.readTree(library).get("guides").size(),
                "and does not see it in the list either");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    // The two shapes the pipeline asks for, in the order it asks for them. Only one section reply
    // is queued because only one of the two planned headings is covered.
    private void scriptTheModel() {
        chatClient.jsonQueue.add(OUTLINE_JSON);
        chatClient.jsonQueue.add(SECTION_JSON);
    }

    // Request the guide, then drive it to a terminal state on this thread.
    private JsonNode writeNow(String topic) throws Exception {
        JsonNode accepted = request(topic);
        String guideId = accepted.get("id").asText();
        runner.run(UUID.fromString(guideId), owner.getId());
        return fetch(guideId, ownerToken);
    }

    private JsonNode request(String topic) throws Exception {
        String body = mockMvc.perform(post(guides())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TopicBody(topic))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode fetch(String guideId, String token) throws Exception {
        String body = mockMvc.perform(get(guides() + "/" + guideId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String guides() {
        return "/api/v1/courses/" + courseId + "/guides";
    }

    private UUID insertGuide(StudyGuideStatus status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into study_guides
                    (id, course_space_id, requested_by, topic, status, language, sections_planned)
                values (?, cast(? as uuid), ?, ?, ?, ?, ?)
                """, id, courseId, owner.getId(), COVERED, status.name(), "ENGLISH", 6);
        return id;
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Guide " + prefix);
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private void addMember(User user) {
        jdbcTemplate.update(
                "insert into memberships (id, course_space_id, user_id, role) values (?, cast(? as uuid), ?, ?)",
                UUID.randomUUID(), courseId, user.getId(), "MEMBER");
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Algorithms", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void ingestPdf(String line) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", "lecture.pdf", "application/pdf", pdfBytes(line)))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        ingestionService.ingest(UUID.fromString(objectMapper.readTree(body).get("id").asText()));
    }

    private byte[] pdfBytes(String line) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(line);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private record TopicBody(String topic) { }
}
