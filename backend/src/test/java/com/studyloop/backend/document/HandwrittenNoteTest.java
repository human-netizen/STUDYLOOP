package com.studyloop.backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRepository;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.StubAiConfig.StubVisionClient;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import com.studyloop.backend.security.JwtService;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 16.3 end to end: a photographed note becomes a Document, is answerable only for the person
// who uploaded it, and becomes course material when a manager promotes it.
//
// **The visibility tests are the ones that matter.** Everything else here is the pipeline doing
// what it already did for a PDF, which is the point of the sub-phase. But a private note reaching
// a stranger's answer is the failure this feature can cause and no earlier phase could, and the
// only place it could happen is the two SQL queries in ChunkSearchRepository — so retrieval is
// driven directly, from three different members, over the same corpus.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class HandwrittenNoteTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private StubVisionClient vision;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void switchTheReaderOn() {
        vision.reset();
        // Only this class wants a live vision client; see StubVisionClient for why it ships off.
        vision.configured = true;
    }

    @AfterEach
    void switchTheReaderOff() {
        vision.reset();
    }

    // ── the happy path ──────────────────────────────────────────────────────────────────────

    @Test
    void aPhotographBecomesAReadyDocumentWithoutAnyNewPipeline() throws Exception {
        Fixture fixture = fixture();

        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);

        Document note = documentRepository.findById(noteId).orElseThrow();
        assertThat(note.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(note.getSource()).isEqualTo(DocumentSource.HANDWRITTEN);
        assertThat(note.getVisibility()).isEqualTo(DocumentVisibility.OWNER);
        assertThat(note.getPageCount()).isEqualTo(1);
        // One image is one vision page, so `documents.vision_pages` still describes how the corpus
        // was built — the number the eval report reads.
        assertThat(note.getVisionPages()).isEqualTo(1);
    }

    @Test
    void anyMemberMayAddANoteThoughOnlyAManagerMayAddMaterial() throws Exception {
        Fixture fixture = fixture();

        // The one document a student may put in a course. It is theirs, and adding it changes
        // nothing anybody else can see.
        mockMvc.perform(multipart("/api/v1/courses/" + fixture.courseId + "/notes")
                        .file(photo())
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isAccepted());

        // The same person uploading the same image as course material is still refused.
        mockMvc.perform(multipart("/api/v1/courses/" + fixture.courseId + "/documents")
                        .file(photo())
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPdfIsNotANoteAndAPhotographIsNotMaterial() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(multipart("/api/v1/courses/" + fixture.courseId + "/notes")
                        .file(new MockMultipartFile("file", "lecture.pdf", "application/pdf",
                                "%PDF-1.4".getBytes()))
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isUnsupportedMediaType());

        mockMvc.perform(multipart("/api/v1/courses/" + fixture.courseId + "/documents")
                        .file(photo())
                        .header("Authorization", "Bearer " + fixture.ownerToken))
                .andExpect(status().isUnsupportedMediaType());
    }

    // ── visibility ──────────────────────────────────────────────────────────────────────────

    @Test
    void aPrivateNoteIsRetrievableForItsOwnerAndForNobodyElse() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);

        assertThat(retrieved(fixture.member.getId(), fixture.courseId)).contains(noteId);
        assertThat(retrieved(fixture.other.getId(), fixture.courseId)).doesNotContain(noteId);
        // Not even a manager, who can *read* the note in order to decide about it. Reading it to
        // review is a different act from being answered from it without asking.
        assertThat(retrieved(fixture.owner.getId(), fixture.courseId)).doesNotContain(noteId);
    }

    @Test
    void promotingMakesTheNotePartOfEverybodysCorpus() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);

        mockMvc.perform(post("/api/v1/courses/" + fixture.courseId + "/notes/" + noteId + "/promote")
                        .header("Authorization", "Bearer " + fixture.ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("COURSE"));

        assertThat(retrieved(fixture.other.getId(), fixture.courseId)).contains(noteId);
        assertThat(retrieved(fixture.member.getId(), fixture.courseId)).contains(noteId);
    }

    @Test
    void demotingTakesItBackOutAgain() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);
        promote(fixture, noteId);

        mockMvc.perform(post("/api/v1/courses/" + fixture.courseId + "/notes/" + noteId + "/demote")
                        .header("Authorization", "Bearer " + fixture.ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("OWNER"));

        assertThat(retrieved(fixture.other.getId(), fixture.courseId)).doesNotContain(noteId);
    }

    @Test
    void aPlainMemberCannotPromoteTheirOwnNote() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);

        // The same gate 9.2 puts on accepting a forum answer, and for a stronger reason: a promoted
        // note carries whatever a vision model guessed about somebody's handwriting.
        mockMvc.perform(post("/api/v1/courses/" + fixture.courseId + "/notes/" + noteId + "/promote")
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aNoteThatHasNotFinishedBeingReadCannotBePromoted() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);

        // Promoting mid-pipeline publishes a document with no chunks: retrievable by nobody, cited
        // by nothing, and indistinguishable from a promotion that worked.
        mockMvc.perform(post("/api/v1/courses/" + fixture.courseId + "/notes/" + noteId + "/promote")
                        .header("Authorization", "Bearer " + fixture.ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Note not ready"));
    }

    @Test
    void theNotesListShowsYourOwnNotesAndPromotedOnesAndNoOthers() throws Exception {
        Fixture fixture = fixture();
        UUID mine = uploadNote(fixture);
        ingestionService.ingest(mine);

        // A list that showed more than search does would disclose the existence of a private note
        // even without its text.
        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId + "/notes")
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].mine").value(true));

        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId + "/notes")
                        .header("Authorization", "Bearer " + fixture.otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        promote(fixture, mine);

        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId + "/notes")
                        .header("Authorization", "Bearer " + fixture.otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].mine").value(false));
    }

    @Test
    void aNoteDoesNotAppearInTheCoursesMaterialsList() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);
        promote(fixture, noteId);

        // Even promoted. It is retrievable and citable, and no upload to the materials endpoint
        // put it there — listing it would read as something a manager had uploaded.
        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId + "/documents")
                        .header("Authorization", "Bearer " + fixture.ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void namingAPrivateNotesIdIsNotAWayAroundTheVisibilityRule() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);

        // Retrieval filters by visibility, but three features take a document id *from the
        // request* and read that document's chunks directly — a quiz over named documents, a
        // flashcard deck from one document, and a summary. Each is a way to read a classmate's
        // notes without going near search, so each is scoped the same way.
        mockMvc.perform(post("/api/v1/courses/" + fixture.courseId + "/flashcards/generate")
                        .header("Authorization", "Bearer " + fixture.otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + noteId + "\",\"count\":5}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/courses/" + fixture.courseId + "/quizzes")
                        .header("Authorization", "Bearer " + fixture.otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIds\":[\"" + noteId + "\"],"
                                + "\"multipleChoiceCount\":2,\"shortAnswerCount\":0}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId
                        + "/documents/" + noteId + "/summary")
                        .header("Authorization", "Bearer " + fixture.otherToken))
                .andExpect(status().isNotFound());

        // And the owner is not locked out of their own note by the same rule.
        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId
                        + "/documents/" + noteId + "/summary")
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isOk());
    }

    // ── the review view ─────────────────────────────────────────────────────────────────────

    @Test
    void theBlocksTheModelWasUnsureOfAreShownAndAreNotIndexed() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);

        // Both blocks come back, with the confidences the model reported — this is the screen that
        // makes the threshold honest rather than merely cautious.
        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId + "/notes/" + noteId + "/blocks")
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].indexed").value(true))
                .andExpect(jsonPath("$[1].indexed").value(false))
                .andExpect(jsonPath("$[1].content").value("T(n) = [illegible] + O(n)"));

        // And the dropped one is genuinely absent from what a search can reach.
        List<RetrievedChunk> hits = retrievalService.retrieve(
                fixture.member.getId(), UUID.fromString(fixture.courseId), "illegible", 10);
        assertThat(hits).noneMatch(hit -> hit.content().contains("[illegible]"));
    }

    @Test
    void aClassmateCannotReadTheBlocksOfAPrivateNote() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);

        // 404 rather than 403: whether a classmate has photographed a particular page is itself
        // the thing being kept private, and 403 would confirm the note exists.
        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId + "/notes/" + noteId + "/blocks")
                        .header("Authorization", "Bearer " + fixture.otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void aManagerMayReadWhatTheyAreBeingAskedToPromote() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);

        mockMvc.perform(get("/api/v1/courses/" + fixture.courseId + "/notes/" + noteId + "/blocks")
                        .header("Authorization", "Bearer " + fixture.ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── the export ──────────────────────────────────────────────────────────────────────────

    @Test
    void theNoteCanBeDownloadedAsLatexAndCarriesOnlyWhatWasIndexed() throws Exception {
        Fixture fixture = fixture();
        UUID noteId = uploadNote(fixture);
        ingestionService.ingest(noteId);

        String tex = mockMvc.perform(
                        get("/api/v1/courses/" + fixture.courseId + "/notes/" + noteId + "/latex")
                                .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-tex"))
                .andReturn().getResponse().getContentAsString();

        assertThat(tex).contains("\\section{Amortized Analysis}");
        // An exported .tex leaves the system and nothing travels with it to say which sentences
        // were guesses, so the low-confidence block is not in it either.
        assertThat(tex).doesNotContain("illegible");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private record Fixture(String courseId, User owner, String ownerToken,
                           User member, String memberToken, User other, String otherToken) { }

    private Fixture fixture() throws Exception {
        User owner = saveUser();
        String ownerToken = jwtService.generateAccessToken(owner);
        String courseId = createCourse(ownerToken);
        User member = saveUser();
        User other = saveUser();
        addMember(courseId, member);
        addMember(courseId, other);
        return new Fixture(courseId, owner, ownerToken, member,
                jwtService.generateAccessToken(member), other,
                jwtService.generateAccessToken(other));
    }

    private UUID uploadNote(Fixture fixture) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + fixture.courseId + "/notes")
                        .file(photo())
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private void promote(Fixture fixture, UUID noteId) throws Exception {
        mockMvc.perform(post("/api/v1/courses/" + fixture.courseId + "/notes/" + noteId + "/promote")
                        .header("Authorization", "Bearer " + fixture.ownerToken))
                .andExpect(status().isOk());
    }

    private List<UUID> retrieved(UUID actorId, String courseId) {
        return retrievalService
                .retrieve(actorId, UUID.fromString(courseId), "amortized analysis resize", 10)
                .stream()
                .map(RetrievedChunk::documentId)
                .toList();
    }

    // A tiny but genuine PNG header. The extractor sniffs the bytes rather than trusting the
    // content type, and the stub reader never looks at the pixels.
    private static MockMultipartFile photo() {
        byte[] bytes = new byte[128];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        return new MockMultipartFile("file", "notebook-page.png", "image/png", bytes);
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
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

    private void addMember(String courseId, User user) {
        CourseSpace course = courseSpaceRepository.findById(UUID.fromString(courseId)).orElseThrow();
        Membership membership = new Membership();
        membership.setCourseSpace(course);
        membership.setUser(user);
        membership.setRole(MembershipRole.MEMBER);
        membershipRepository.saveAndFlush(membership);
    }
}
