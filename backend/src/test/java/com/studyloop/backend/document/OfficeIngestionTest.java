package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import com.studyloop.backend.security.JwtService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 16's "done when", for the two office formats: a .pptx lecture and a .docx both reach READY
// and answer a question with a citation that points at the right place.
//
// The extractor tests already pin the Markdown. What these add is the half that cannot be asserted
// without the whole application: that the *rest* of the pipeline needed nothing. The upload
// endpoint accepts them, the registry routes them, the chunker cuts them on their own headings,
// the embedder embeds them, retrieval finds them, and the citation carries a slide number a
// student can act on — none of which was written for PowerPoint or Word.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class OfficeIngestionTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aLectureDeckReachesReadyAndIsCitedBySlide() throws Exception {
        User actor = saveUser();
        String token = tokenFor(actor);
        String courseId = createCourse(token);

        byte[] deck = TestOfficeFiles.deck()
                .slide("Amortized Analysis",
                        List.of("A resize costs O(n)", "Appends before it were free"),
                        "The trick is that the expensive resize pays for the cheap appends.")
                .slide("Skiplists", List.of("Expected search time is logarithmic"), null)
                .bytes();
        UUID documentId = upload(courseId, token, file("week4.pptx", deck,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        ingestionService.ingest(documentId);

        Document document = documentRepository.findById(documentId).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
        // One page per slide, so a citation's page number is the number in the corner of the slide.
        assertThat(document.getPageCount()).isEqualTo(2);
        // Read locally: a deck costs no provider call before the embedder.
        assertThat(document.getVisionPages()).isZero();

        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(documentId);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.getSectionPath()).startsWith("Slide "));

        List<RetrievedChunk> hits = retrievalService.retrieve(
                actor.getId(), UUID.fromString(courseId),
                "expected search time skiplists", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).sectionPath()).isEqualTo("Slide 2: Skiplists");
        assertThat(hits.get(0).pageNumber()).isEqualTo(2);
    }

    @Test
    void theSpeakerNotesAreSearchableAndStillLabelledOnceTheyAreChunks() throws Exception {
        User actor = saveUser();
        String token = tokenFor(actor);
        String courseId = createCourse(token);

        byte[] deck = TestOfficeFiles.deck()
                .slide("Amortized Analysis", List.of("O(1) per operation"),
                        "Doubling the array means each element is copied at most twice overall.")
                .bytes();
        UUID documentId = upload(courseId, token, file("week4.pptx", deck,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        ingestionService.ingest(documentId);

        // The notes are usually the only prose in a deck, so a question phrased like a sentence
        // should reach them at all — which it cannot if the export dropped them.
        List<RetrievedChunk> hits = retrievalService.retrieve(
                actor.getId(), UUID.fromString(courseId),
                "each element is copied at most twice", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).content()).contains("copied at most twice");
        // And they still say they are notes rather than material, which is the point of the
        // `## Speaker notes` heading surviving into the chunk.
        assertThat(hits.get(0).content()).contains("Speaker notes");
    }

    @Test
    void aWordDocumentReachesReadyAndCarriesItsHeadingHierarchy() throws Exception {
        User actor = saveUser();
        String token = tokenFor(actor);
        String courseId = createCourse(token);

        // Sections of a realistic length, deliberately. The chunker absorbs a section under 120
        // tokens into the sibling after it (a one-sentence subsection is a fragment, not a
        // retrieval unit), so a fixture of two one-liners would merge into their shared parent and
        // this test would be asserting the chunker's merge rule rather than Word's headings.
        byte[] docx = TestOfficeFiles.doc()
                .heading("Heading1", "Graph Algorithms")
                .heading("Heading2", "Dijkstra")
                .paragraph("Dijkstra's algorithm grows a set of settled vertices, repeatedly "
                        + "removing the unsettled vertex with the smallest tentative distance and "
                        + "relaxing every edge leaving it. The argument that a settled vertex is "
                        + "final depends on every edge weight being non-negative: if some later "
                        + "path could arrive more cheaply, it would have to pass through a vertex "
                        + "already known to be further away, and adding a non-negative weight to a "
                        + "larger distance cannot produce a smaller one. With a binary heap the "
                        + "running time is O((V + E) log V), and with a Fibonacci heap the decrease "
                        + "key operation becomes amortised constant, which brings it to "
                        + "O(E + V log V) at the cost of a much larger constant factor in practice.")
                .heading("Heading2", "Bellman-Ford")
                .paragraph("Bellman-Ford abandons the settled set and simply relaxes every edge in "
                        + "the graph, V minus one times over. That is slower, at O(VE), and it buys "
                        + "the one thing Dijkstra cannot offer: correctness in the presence of "
                        + "negative edge weights. A shortest path visits at most V minus one edges, "
                        + "so after that many rounds every reachable distance has converged. Running "
                        + "one further round is what turns the algorithm into a detector as well as "
                        + "a solver, because any edge that still relaxes on the extra pass belongs "
                        + "to a cycle whose total weight is negative, and around such a cycle there "
                        + "is no shortest path at all. Detecting negative cycles this way is why the "
                        + "algorithm survives in currency arbitrage and in distance-vector routing.")
                .bytes();
        UUID documentId = upload(courseId, token, file("notes.docx", docx,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        ingestionService.ingest(documentId);

        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.READY);

        List<RetrievedChunk> hits = retrievalService.retrieve(
                actor.getId(), UUID.fromString(courseId),
                "negative cycles detection", 5);
        assertThat(hits).isNotEmpty();
        // The heading trail the author declared, carried all the way into the citation — this is
        // what a PDF of the same document would have had to infer from font sizes.
        assertThat(hits.get(0).sectionPath()).isEqualTo("Graph Algorithms > Bellman-Ford");
    }

    @Test
    void theDownloadEndpointServesTheFormatItStored() throws Exception {
        User actor = saveUser();
        String token = tokenFor(actor);
        String courseId = createCourse(token);

        byte[] deck = TestOfficeFiles.deck()
                .slide("Graphs", List.of("Adjacency list"), null)
                .bytes();
        // Uploaded with no content type at all, which is what curl sends and what a browser sends
        // when it does not know the extension. The stored type is the format's own.
        UUID documentId = upload(courseId, token,
                new MockMultipartFile("file", "week4.pptx", null, deck));

        assertThat(documentRepository.findById(documentId).orElseThrow().getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");

        // Served as itself rather than as a PDF. A .pptx labelled `application/pdf` reaches a PDF
        // viewer that cannot open it, and the symptom is a blank pane rather than a download.
        mockMvc.perform(get("/api/v1/courses/" + courseId + "/documents/" + documentId + "/file")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static MockMultipartFile file(String name, byte[] bytes, String contentType) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    private UUID upload(String courseId, String token, MockMultipartFile file) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.generateAccessToken(user);
    }

    private String createCourse(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Algorithms", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
