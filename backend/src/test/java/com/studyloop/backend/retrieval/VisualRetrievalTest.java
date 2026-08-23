package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.ChunkModality;
import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentChunk;
import com.studyloop.backend.document.DocumentChunkRepository;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.DocumentRepository;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.StubAiConfig.StubEmbeddingClient;
import com.studyloop.backend.document.TestPdfs;
import com.studyloop.backend.document.VectorSupport;
import com.studyloop.backend.document.TestPdfs.Kind;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 17's "done when": a question about a diagram returns the page it is on, and the page
// arrives at the generator as words.
//
// **What a stub can and cannot show here, stated up front.** It cannot show that embed-v4.0 puts a
// picture of a page near a sentence describing it — that is a judgement about a provider, and it
// belongs to the eval harness and a real key. What it can show, and what nothing else does, is
// every piece of wiring between the two: that a figure page becomes a row with a vector made from
// its image, that the two text halves of hybrid search cannot see that row, that the third list
// can, that RRF fuses it like any other candidate, and that what comes back carries words a
// text-only model can use.
//
// The stub embedder is told what each page image "means" and embeds that string, so a query using
// the same words matches it exactly. Every assertion below is about which list found the row,
// never about how good the match was.
@SpringBootTest(properties = "studyloop.retrieval.stages.visual=true")
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class VisualRetrievalTest {

    // What the stub is told the figure page looks like. Deliberately shares no vocabulary with the
    // page's own text, so a hit on these words can only have come through the image vector.
    private static final String PICTURE = "a binary tree drawn with circles and edges";

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
    private ChunkSearchRepository searchRepository;

    @Autowired
    private StubEmbeddingClient embedder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void resetStub() {
        embedder.reset();
    }

    @Test
    void aFigurePageIsIndexedAsAPictureAndCitedByItsPage() throws Exception {
        Fixture fixture = ingestFigureDocument();

        List<DocumentChunk> visuals = chunkRepository
                .findByDocumentIdAndModalityOrderByChunkIndex(fixture.documentId, ChunkModality.VISUAL);
        assertThat(visuals).hasSize(1);
        // Page 2 is the drawn figure; pages 1 and 3 are prose and cost nothing.
        assertThat(visuals.get(0).getPageNumber()).isEqualTo(2);
        assertThat(visuals.get(0).getPageEnd()).isEqualTo(2);
        // No section path, deliberately: a visual chunk is a page rather than a section, and one
        // would make small-to-big expansion splice a whole section's prose around a figure.
        assertThat(visuals.get(0).getSectionPath()).isNull();
        // Appended after the text chunks, continuing the index sequence the unique constraint uses.
        List<DocumentChunk> texts = chunkRepository
                .findByDocumentIdAndModalityOrderByChunkIndex(fixture.documentId, ChunkModality.TEXT);
        assertThat(visuals.get(0).getChunkIndex()).isEqualTo(texts.size());
        assertThat(embedder.imageCalls.get()).isEqualTo(1);

        List<RetrievedChunk> hits = retrievalService.retrieve(
                fixture.actorId, fixture.courseId, PICTURE, 6);

        // Asserted on the visual hit rather than on `hits.get(0)`, and the distinction is a real
        // property of RRF rather than a hedge: the leading chunk of every list scores exactly
        // 1/(K+1), so the head of a three-list fusion is a tie decided by the tie-break rule and
        // not by the picture matching perfectly. What this phase promises is that the page comes
        // back, which is what the golden set grades and what a citation needs.
        assertThat(hits).isNotEmpty();
        List<RetrievedChunk> visualHits = hits.stream().filter(RetrievedChunk::visual).toList();
        assertThat(visualHits).hasSize(1);
        RetrievedChunk figure = visualHits.get(0);
        assertThat(figure.pageNumber()).isEqualTo(2);
        // Command R is text-only. A chunk found through a picture still has to arrive as words, or
        // it could be retrieved and then not used.
        assertThat(figure.content()).isNotBlank();
        assertThat(figure.content()).contains("page 2");
    }

    @Test
    void theTextHalvesOfHybridSearchCannotSeeAVisualChunk() throws Exception {
        Fixture fixture = ingestFigureDocument();
        UUID visualId = chunkRepository
                .findByDocumentIdAndModalityOrderByChunkIndex(fixture.documentId, ChunkModality.VISUAL)
                .get(0).getId();
        String pageWords = "A skiplist is a sequence of singly linked lists";
        // The vector the pipeline itself embedded this question with, handed back by search()
        // precisely so a caller does not have to pay to embed the same string twice.
        String literal = VectorSupport.toLiteral(retrievalService
                .search(fixture.actorId, fixture.courseId, pageWords, 6).queryVector());

        // Asserted against the two queries themselves rather than against the fused result, because
        // the fused result is the wrong place to look: the third list contributes its best
        // candidate to *every* question, which is a property of RRF rather than of the filter being
        // checked here — see the test below.
        //
        // The visual chunk's text is a copy of its page's text, so a query made of that page's own
        // words would match it lexically, and its vector sits in the same column the dense half
        // orders by. Both halves exclude it by modality, which is what stops a figure page taking
        // two of the six slots with the same words twice, and what keeps the twenty candidates each
        // half is asked for twenty *text* candidates.
        assertThat(searchRepository.fullTextSearch(fixture.courseId, fixture.actorId, pageWords, 20))
                .isNotEmpty()
                .noneMatch(hit -> hit.id().equals(visualId));
        assertThat(searchRepository.vectorSearch(fixture.courseId, fixture.actorId, literal, 20))
                .isNotEmpty()
                .allMatch(hit -> hit.modality() == ChunkModality.TEXT);
        // And the third list is the only one that can reach it.
        assertThat(searchRepository.visualSearch(fixture.courseId, fixture.actorId, literal, 20))
                .extracting(ChunkHit::id)
                .containsExactly(visualId);
    }

    @Test
    void athirdListContributesItsBestCandidateToEveryQuestion() throws Exception {
        Fixture fixture = ingestFigureDocument();
        UUID visualId = chunkRepository
                .findByDocumentIdAndModalityOrderByChunkIndex(fixture.documentId, ChunkModality.VISUAL)
                .get(0).getId();

        // Written down because it is the cost of the design rather than an accident of this
        // fixture. Reciprocal Rank Fusion reads *positions*, so a list of one page ranks that page
        // first and contributes 1/(60+1) for it — the same contribution the best passage in the
        // corpus makes — however far the picture actually is from the question. A third list can
        // only ever add candidates, never remove them.
        //
        // Two things are meant to absorb that and both are outside this test: the cross-encoder,
        // which scores a figure page on its words and drops it when they do not answer, and the
        // eval harness, which is the only thing that can say whether the trade is worth making on
        // a real corpus. Until it has, `stages.visual` ships off. If a later phase adds a floor to
        // the visual list, this test is the one that should fail.
        List<RetrievedChunk> hits = retrievalService.retrieve(
                fixture.actorId, fixture.courseId, "an unrelated question about hash tables", 6);

        assertThat(hits).anyMatch(hit -> hit.chunkId().equals(visualId));
    }

    @Test
    void anEmbedderThatTakesNoImagesLeavesTheCorpusExactlyAsPhase16BuiltIt() throws Exception {
        // The default for two of the three providers this project supports, and the reason the
        // selector asks before it renders: rows with no vector would be a second copy of every
        // figure page's text that no query can reach.
        Fixture fixture = ingest(false);

        assertThat(chunkRepository.countByDocumentIdAndModality(
                fixture.documentId, ChunkModality.VISUAL)).isZero();
        assertThat(embedder.imageCalls.get()).isZero();
        assertThat(documentRepository.findById(fixture.documentId).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.READY);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private record Fixture(UUID actorId, UUID courseId, UUID documentId) { }

    private Fixture ingestFigureDocument() throws Exception {
        return ingest(true);
    }

    private Fixture ingest(boolean images) throws Exception {
        embedder.images = images;
        embedder.imageMeaning = png -> PICTURE;

        User actor = saveUser();
        String token = jwtService.generateAccessToken(actor);
        UUID courseId = UUID.fromString(createCourse(token));

        byte[] pdf = TestPdfs.of(Kind.PROSE, Kind.VECTOR_FIGURE, Kind.PROSE);
        UUID documentId = upload(courseId, token,
                new MockMultipartFile("file", "chapter.pdf", "application/pdf", pdf));
        ingestionService.ingest(documentId);

        Document document = documentRepository.findById(documentId).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
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
        user.setEmail("visual-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Visual Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Visual retrieval", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
