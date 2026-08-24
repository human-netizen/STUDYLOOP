package com.studyloop.backend.video;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 21 — everything about a video except the video.
//
// The renderer is stubbed (see StubVideoWorker for why that is the honest boundary), so what these
// tests hold to account is the half AddNewFeature.md §4 said did not exist: a queue with states and
// failures, a refusal that costs nothing, a fallback that is counted and explained, citations that
// survive to the player, and a restart that does not leave a job at 40% forever.
//
// **The render is driven synchronously.** In production the job starts from an AFTER_COMMIT event
// on the single-slot executor; inside a @Transactional test nothing ever commits, so the listener
// would never fire and the assertions would be about an empty row. Calling the runner directly is
// the same arrangement DocumentIngestionTest uses, and it tests the thing worth testing — what the
// runner does — rather than Spring's event plumbing.
//
// **No `properties` and no second `@Import`, deliberately.** Both would give this class its own
// cached ApplicationContext, and each context opens its own Hikari pool against a session pooler
// that allows fifteen clients in total — which is exactly what happened when this was written with
// three property overrides: the suite reached eight pools and this context could not start, while
// the class passed perfectly when run on its own. The feature flag and the videos directory live in
// `application-test.yml`, where they apply to every context uniformly, and the stub renderer is a
// bean of `StubAiConfig`, which this shares with every other integration test.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class VideoGenerationTest {

    // Every content word here is in the ingested page. That is not laziness about phrasing: with
    // no reranker configured the confidence gate falls back to Phase 5.3's rule — weak cosine AND
    // no lexical hit — and the stub embedder's cosines are deterministic noise, so the lexical
    // half is what has to carry a covered topic past the gate.
    private static final String COVERED = "How does dynamic programming combine subproblem solutions?";
    private static final String UNCOVERED = "Which airline flies to Reykjavik?";

    // Two scenes, the first animated. Short on purpose: the shape is what is under test, not the
    // model's taste.
    private static final String SCRIPT_JSON = """
            {"scenes":[
              {"title":"Overlapping subproblems","narration":"The same subproblem appears again and again.","sources":[1]},
              {"title":"What to remember","narration":"Store each answer once and reuse it.","sources":[1]}
            ]}
            """;

    private static final String SCENE_PLAN_JSON = """
            {"scenes":[
              {"index":1,"visual":"ANIMATED","description":"A tree with two identical subtrees highlighted.",
               "bullets":["Same subproblem, twice","Exponential without memory"]},
              {"index":2,"visual":"STATIC","description":null,
               "bullets":["Solve once","Reuse the answer"]}
            ]}
            """;

    private static final String MANIM_CODE = """
            from manim import *

            class GeneratedScene(Scene):
                def construct(self):
                    self.play(Write(Text("Overlapping subproblems")))
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
    private RecordingVideoWorker worker;

    @Autowired
    private VideoJobRunner runner;

    @Autowired
    private VideoJobReconciler reconciler;

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
        worker.reset();
    }

    // The whole path, end to end: a topic the corpus covers becomes a finished job whose scenes
    // carry the citations they were written from.
    @Test
    void aCoveredTopicBecomesAVideoWhoseScenesCiteTheirSources() throws Exception {
        scriptTheModel();

        JsonNode job = renderNow(COVERED);

        assertEquals("READY", job.get("status").asText());
        assertEquals(2, job.get("scenesTotal").asInt());
        assertEquals(1, job.get("scenesAnimated").asInt());
        assertEquals(1, job.get("scenesFallback").asInt());
        assertTrue(job.get("hasCaptions").asBoolean(), "edge-tts word timings become a VTT track");

        JsonNode first = job.get("scenes").get(0);
        assertEquals("Overlapping subproblems", first.get("title").asText());
        assertEquals("ANIMATED", first.get("renderedAs").asText());
        assertTrue(first.get("citations").size() > 0, "a scene written from a chunk must cite it");
        assertNotNull(first.get("citations").get(0).get("documentId").asText());
        // The second scene was planned as a slide, so it is not a fallback and has no reason to
        // give. Conflating the two would make the fallback count mean two different things.
        assertTrue(job.get("scenes").get(1).get("fallbackReason").isNull());
    }

    // **The refusal, and where it happens.** AddNewFeature.md §4's argument was that the most
    // expensive action in the product would fail expensively. It fails for the price of one
    // embedding: the renderer is never contacted at all.
    @Test
    void anUncoveredTopicIsRefusedBeforeTheRendererIsContacted() throws Exception {
        scriptTheModel();

        JsonNode job = renderNow(UNCOVERED);

        assertEquals("REFUSED", job.get("status").asText());
        assertTrue(job.get("error").asText().startsWith("I don't have that"),
                () -> "a refusal reads like chat's refusal: " + job.get("error").asText());
        assertEquals(0, job.get("scenesTotal").asInt());
        assertTrue(worker.callsMatching("animate").isEmpty(), "nothing was rendered");
        assertTrue(worker.callsMatching("narrate").isEmpty(), "nothing was spoken");
        assertEquals(0, chatClient.calls.get(), "and no model call was made either");
    }

    // The fallback is the objection this phase was rejected over: it happens, and it must not be
    // silent. The scene says which layer stopped it, and the job says how many scenes that was.
    @Test
    void aRejectedSceneBecomesASlideThatSaysWhy() throws Exception {
        scriptTheModel();
        worker.animateFailsAtLayer = "REJECTED";

        JsonNode job = renderNow(COVERED);

        assertEquals("READY", job.get("status").asText());
        assertEquals(0, job.get("scenesAnimated").asInt());
        assertEquals(2, job.get("scenesFallback").asInt());

        JsonNode fell = job.get("scenes").get(0);
        assertEquals("SLIDE", fell.get("renderedAs").asText());
        assertTrue(fell.get("fallbackReason").asText().startsWith("REJECTED"),
                () -> "the layer is named: " + fell.get("fallbackReason").asText());
    }

    // The fix loop is bounded, and the bound is what stops one bad scene from eating the machine.
    // Two fixes at most, so three attempts, and then the scene is a slide.
    @Test
    void theFixLoopStopsAfterTwoAttemptsAtRepair() throws Exception {
        scriptTheModel();
        worker.animateFailsAtLayer = "RENDER";

        renderNow(COVERED);

        assertEquals(3, worker.animateAttempts.get(),
                "one attempt plus at most two fixes — never a fourth");
    }

    // **The startup sweep.** An in-process queue loses its work on every restart, and the only
    // unacceptable version of that is the job that says "rendering scene 3 of 6" forever.
    @Test
    void aRestartFailsJobsTheLastProcessLeftMidFlight() throws Exception {
        UUID jobId = insertJob(VideoJobStatus.RENDERING);

        reconciler.failInterruptedJobs();

        JsonNode job = fetch(jobId.toString(), ownerToken);
        assertEquals("FAILED", job.get("status").asText());
        assertTrue(job.get("error").asText().contains("interrupted by a server restart"),
                () -> job.get("error").asText());
    }

    // A video can be grounded on the requester's own private notes, so it inherits their
    // visibility rather than the course's — 20.1's rule, applied to a new artifact. A classmate
    // gets the same 404 they would get for a job that does not exist.
    @Test
    void anotherMembersVideoIsNotReachable() throws Exception {
        scriptTheModel();
        JsonNode mine = renderNow(COVERED);

        User classmate = saveUser("classmate");
        addMember(classmate);
        String theirToken = jwtService.generateAccessToken(classmate);

        mockMvc.perform(get(videos() + "/" + mine.get("id").asText())
                        .header("Authorization", "Bearer " + theirToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(videos() + "/" + mine.get("id").asText() + "/file")
                        .header("Authorization", "Bearer " + theirToken))
                .andExpect(status().isNotFound());
    }

    // The per-member daily cap — three, from the shipped configuration rather than from a value
    // this test set for itself, so what it pins is the limit the application actually enforces. One
    // video is an order of magnitude more expensive than a chat answer in model calls and several
    // orders of magnitude more in wall clock, so the quota that counts requests cannot be the only
    // limit.
    @Test
    void theDailyCapRefusesTheNextRequest() throws Exception {
        request(COVERED);
        request(COVERED);
        request(COVERED);

        mockMvc.perform(post(videos())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TopicBody(COVERED))))
                .andExpect(status().isTooManyRequests());
    }

    // A refusal is not a use of the allowance. Asking about something the corpus does not cover
    // costs one embedding, and charging a day's quota for it would teach students not to ask.
    @Test
    void aRefusedJobDoesNotCountAgainstTheDailyCap() throws Exception {
        scriptTheModel();
        renderNow(UNCOVERED);

        JsonNode library = library();
        assertEquals(0, library.get("usedToday").asInt());
    }

    // The library is one call because the flag and the list have to agree: a button drawn from a
    // stale flag is the dead UI the flag exists to prevent.
    @Test
    void theLibrarySaysWhetherThisInstallationCanRenderAtAll() throws Exception {
        assertTrue(library().get("enabled").asBoolean());
        assertTrue(library().get("workerReachable").asBoolean());

        worker.up = false;
        JsonNode down = library();
        assertTrue(down.get("enabled").asBoolean(), "the feature exists");
        assertFalse(down.get("workerReachable").asBoolean(), "the renderer does not, right now");
    }

    // Requesting with the renderer down is refused at the door rather than accepted and failed
    // four seconds later — the one thing a job row cannot make better.
    @Test
    void aRequestIsRefusedWhileTheRendererIsDown() throws Exception {
        worker.up = false;

        mockMvc.perform(post(videos())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TopicBody(COVERED))))
                .andExpect(status().isServiceUnavailable());
    }

    // Three model calls per video with one animated scene, and they are three different asks.
    //
    // The cost of this feature is not one number: a script is paid per video, and Manim code is
    // paid per animated scene and again per fix. AiOperation keeps them apart for the dashboard;
    // what this asserts is the thing the dashboard depends on — that they really are three
    // distinct calls with three distinct prompts, rather than one prompt reused.
    @Test
    void theThreeModelCallsAreThreeDifferentAsks() throws Exception {
        scriptTheModel();

        renderNow(COVERED);

        assertEquals(3, chatClient.calls.get(), "script, scene plan, and one scene of Manim");
        assertEquals(3, chatClient.prompts.size());
        assertTrue(chatClient.prompts.get(0).contains("video script writer"), chatClient.prompts.get(0));
        assertTrue(chatClient.prompts.get(1).contains("storyboard planner"), chatClient.prompts.get(1));
        assertTrue(chatClient.prompts.get(2).contains("Manim"), chatClient.prompts.get(2));
    }

    // The prompt tells the model the sandbox's rules, and the sandbox does not trust the prompt.
    // Both halves matter: without the prompt every scene would be rejected, and without the
    // allow-list the prompt would be the only thing standing between a model and `open`.
    @Test
    void theCodePromptStatesTheRulesTheSandboxEnforces() throws Exception {
        scriptTheModel();

        renderNow(COVERED);

        String prompt = chatClient.prompts.get(2);
        assertTrue(prompt.contains("from manim import *"), prompt);
        assertTrue(prompt.contains("No `open`"), prompt);
        assertTrue(prompt.contains("two underscores"), prompt);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    // The three shapes the model is asked for, in the order the pipeline asks for them.
    private void scriptTheModel() {
        chatClient.jsonQueue.add(SCRIPT_JSON);
        chatClient.jsonQueue.add(SCENE_PLAN_JSON);
        chatClient.nextAnswer = MANIM_CODE;
    }

    // Request the job, then drive it to a terminal state on this thread.
    private JsonNode renderNow(String topic) throws Exception {
        JsonNode accepted = request(topic);
        String jobId = accepted.get("id").asText();
        runner.run(UUID.fromString(jobId), owner.getId());
        return fetch(jobId, ownerToken);
    }

    private JsonNode request(String topic) throws Exception {
        String body = mockMvc.perform(post(videos())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TopicBody(topic))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode fetch(String jobId, String token) throws Exception {
        String body = mockMvc.perform(get(videos() + "/" + jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode library() throws Exception {
        String body = mockMvc.perform(get(videos())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String videos() {
        return "/api/v1/courses/" + courseId + "/videos";
    }

    private UUID insertJob(VideoJobStatus status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into video_jobs (id, course_space_id, requested_by, topic, status, stage)
                values (?, cast(? as uuid), ?, ?, ?, ?)
                """, id, courseId, owner.getId(), COVERED, status.name(), "Rendering scene 3 of 6");
        return id;
    }

    private User saveUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Video " + prefix);
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
