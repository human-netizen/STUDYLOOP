package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.ChunkModality;
import com.studyloop.backend.document.DocumentChunk;
import com.studyloop.backend.document.DocumentChunkRepository;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.DocumentRepository;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.StubAiConfig.RecordingChatClient;
import com.studyloop.backend.document.TestPdfs;
import com.studyloop.backend.document.TestPdfs.Kind;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 18.2, and the test that matters here is `theGateReadsTheQuestionsSimilarityNotTheHypotheticals`.
//
// **The failure it guards against is the one that leaves no trace.** Retrieve with a hypothetical
// answer and report the hypothetical's cosine as the confidence signal, and every score the gate
// reads goes up — not because any answer got better, but because a pseudo-document sits nearer real
// documents than a question does. Nothing throws, no test goes red, latency does not change, and
// the refusal rate quietly falls toward zero: the system starts answering confidently from weak
// context, which is the one behaviour separating this project from a chatbot.
//
// The stub embedder makes that measurable in a way a real provider could not. It keys a vector on
// the exact string, so a hypothetical set equal to a chunk's own embedded text scores **1.000**
// against that chunk, while the question — a different string — scores essentially zero against it.
// One number tells the two designs apart, and it is asserted below.
@QueryUnderstandingTest
class HydeRetrievalTest {

    // A question sharing no vocabulary with anything in the fixture, so the first pass is weak on
    // the lexical half as well as the dense one.
    private static final String QUESTION = "why is the pivot chosen the way it is";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private HydeStage hydeStage;

    @Autowired
    private RecordingChatClient chat;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void resetStub() {
        chat.reset();
    }

    @Test
    void theGateReadsTheQuestionsSimilarityNotTheHypotheticals() throws Exception {
        Fixture fixture = ingest();
        DocumentChunk target = firstTextChunk(fixture.documentId);
        // The hypothetical is set to the chunk's own embedded text, so the stub embeds it to
        // exactly that chunk's vector: cosine 1.000 against it, and nothing else in the corpus.
        expansionReturning(embeddedTextOf(target), "how a pivot is selected");

        RetrievalResult result =
                retrievalService.search(fixture.actorId, fixture.courseId, QUESTION, 6);

        // The second pass ran and found the chunk it was aimed at — otherwise the assertion below
        // would pass for the boring reason that HyDE did nothing.
        assertThat(result.expanded()).isTrue();
        assertThat(result.chunks()).anyMatch(hit -> hit.chunkId().equals(target.getId()));

        // And the number the gate will read is the *question's* distance to what was found, not the
        // hypothetical's. 1.000 here would mean the pipeline had handed the gate a score on a scale
        // it was never calibrated for.
        assertThat(result.topVectorSimilarity()).isPresent();
        assertThat(result.topVectorSimilarity().getAsDouble())
                .as("the hypothetical scores 1.000 against this chunk; the question must not")
                .isLessThan(0.5);
    }

    @Test
    void aStrongFirstPassPaysForNothing() throws Exception {
        ingest();

        // The whole affordability argument: the stage is on, and a question the first pass already
        // answered well never reaches the provider. Asserted on the trigger rather than on a call
        // count, so it says which rule decided.
        assertThat(hydeStage.triggers("a well answered question about sorting", OptionalDouble.of(0.72)))
                .isFalse();
        assertThat(hydeStage.triggers("a well answered question about sorting", OptionalDouble.of(0.31)))
                .isTrue();
    }

    @Test
    void aVeryShortQuestionEarnsASecondPassHoweverWellItScored() throws Exception {
        ingest();

        // One content word is one chance at whichever vocabulary the book happens to use, and a
        // strong cosine on one word is a strong match to one word.
        assertThat(hydeStage.triggers("treaps?", OptionalDouble.of(0.90))).isTrue();
        assertThat(hydeStage.triggers("what is the expected height of a skiplist containing n elements",
                OptionalDouble.of(0.90))).isFalse();
    }

    @Test
    void aProviderOutageLeavesThePipelineExactlyAsItWasWithoutTheStage() throws Exception {
        Fixture fixture = ingest();
        chat.failNext = true;

        // Not a 502 on a question the corpus could answer. The expansion is an optimisation, and an
        // optimisation that can fail the request is a liability.
        RetrievalResult result =
                retrievalService.search(fixture.actorId, fixture.courseId, QUESTION, 6);

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.expanded()).isFalse();
    }

    @Test
    void proseInsteadOfJsonIsTreatedAsNoExpansionRatherThanAsAnError() throws Exception {
        Fixture fixture = ingest();
        chat.nextJson = "I'm sorry, I can't help with that.";

        RetrievalResult result =
                retrievalService.search(fixture.actorId, fixture.courseId, QUESTION, 6);

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.expanded()).isFalse();
    }

    // The model's reading of the question overrules the phrase match, because it has read the
    // question and the phrase match has only pattern-matched it. "why is the pivot chosen the way
    // it is" is an explanation to the classifier; the model calls it a comparison and wins.
    @Test
    void anIntentFromTheExpansionCallOverridesTheRuleBasedOne() throws Exception {
        Fixture fixture = ingest();
        expansionReturning("Quicksort picks a pivot uniformly at random.", "compare");

        RetrievalResult result =
                retrievalService.search(fixture.actorId, fixture.courseId, QUESTION, 6);

        assertThat(new IntentClassifier().classify(QUESTION)).isEqualTo(QueryIntent.EXPLAIN);
        assertThat(result.intent()).isEqualTo(QueryIntent.COMPARE);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private record Fixture(UUID actorId, UUID courseId, UUID documentId) { }

    private void expansionReturning(String hypothetical, String intent) {
        chat.nextJson = objectMapper.createObjectNode()
                .put("intent", intent)
                .put("hypothetical", hypothetical)
                .set("rewrites", objectMapper.createArrayNode().add("how is a pivot picked"))
                .toString();
    }

    // What the stub embedder was actually given for this chunk: the context header plus the text,
    // when 13.4 wrote one, and the text alone otherwise.
    private static String embeddedTextOf(DocumentChunk chunk) {
        return chunk.getEmbedText() != null ? chunk.getEmbedText() : chunk.getContent();
    }

    private DocumentChunk firstTextChunk(UUID documentId) {
        List<DocumentChunk> chunks = chunkRepository
                .findByDocumentIdAndModalityOrderByChunkIndex(documentId, ChunkModality.TEXT);
        assertThat(chunks).isNotEmpty();
        return chunks.get(0);
    }

    private Fixture ingest() throws Exception {
        User actor = saveUser();
        String token = jwtService.generateAccessToken(actor);
        UUID courseId = UUID.fromString(createCourse(token));

        byte[] pdf = TestPdfs.of(Kind.TWO_COLUMN, Kind.PROSE);
        UUID documentId = upload(courseId, token,
                new MockMultipartFile("file", "sorting.pdf", "application/pdf", pdf));
        ingestionService.ingest(documentId);

        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.READY);
        // Ingestion goes through the same stub chat client, so the counter has to start from the
        // question rather than from the upload.
        chat.reset();
        return new Fixture(actor.getId(), courseId, documentId);
    }

    private UUID upload(UUID courseId, String token, MockMultipartFile file) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("hyde-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Hyde Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("HyDE retrieval", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
