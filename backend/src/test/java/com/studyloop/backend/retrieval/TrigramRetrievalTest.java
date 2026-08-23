package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.DocumentRepository;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.DocumentVisibility;
import com.studyloop.backend.document.TestPdfs;
import com.studyloop.backend.document.TestPdfs.Kind;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 18.1's "done when": a question with a misspelled word still returns the page that answers
// it, and the reason it did not before is visible in the same test.
//
// **The first assertion in each test is the failure, not the fix**, because the failure is the part
// nothing else in this suite can see. A typo does not weaken `plainto_tsquery` — it empties it, and
// an empty sparse ranking is indistinguishable in the fused output from a sparse ranking that
// simply agreed with the dense one. Asserting that `fullTextSearch` returns nothing for the same
// question the trigram list answers is the only way to state what is being repaired.
//
// The dense half runs here too, against a stub embedder, and it is deliberately not what any
// assertion depends on: the stub keys a vector on the exact string, so a misspelled question embeds
// nowhere near its correctly spelled page and the dense list is noise. That is realistic in
// direction — a real subword tokenizer degrades rather than fails — and it means the chunk arriving
// through the trigram list arrived through the trigram list.
@QueryUnderstandingTest
class TrigramRetrievalTest {

    // Two ordinary misspellings of two words that are on the page: a transposition and a dropped
    // letter. Neither produces a lexeme any chunk carries.
    private static final String TYPO_QUERY = "how does quicksrot compare with heapsrot";
    private static final String CORRECT_QUERY = "how does quicksort compare with heapsort";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private ChunkSearchRepository searchRepository;

    @Autowired
    private TrigramStage trigramStage;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void theLexemeListIsEmptyOnATypoAndTheTrigramListIsNot() throws Exception {
        Fixture fixture = ingest();

        // The failure. `plainto_tsquery('english', 'quicksrot')` is the lexeme 'quicksrot', which
        // is in no document ever written, so the entire sparse half of hybrid retrieval returns
        // nothing for a question one keystroke away from a good one.
        assertThat(searchRepository.fullTextSearch(fixture.courseId, fixture.actorId, TYPO_QUERY, 20))
                .as("the lexeme half is expected to fail on a typo — that is what this stage is for")
                .isEmpty();
        // And the control: spelled correctly, the same question finds the same corpus.
        assertThat(searchRepository.fullTextSearch(fixture.courseId, fixture.actorId, CORRECT_QUERY, 20))
                .isNotEmpty();

        // The fix, at the level the fix lives at.
        List<ChunkHit> fuzzy = trigramStage.search(fixture.courseId, fixture.actorId, TYPO_QUERY);
        assertThat(fuzzy).isNotEmpty();
        assertThat(fuzzy.get(0).content()).containsIgnoringCase("quicksort");
    }

    @Test
    void aMisspelledQuestionReachesThePageThroughTheFusedPipeline() throws Exception {
        Fixture fixture = ingest();

        List<RetrievedChunk> hits =
                retrievalService.retrieve(fixture.actorId, fixture.courseId, TYPO_QUERY, 6);

        // The wiring assertion: the stage is constructed, switched on by the property above, and
        // its hits are fused like any other list. Which position they land in is RRF's business —
        // the leading chunk of every list contributes the same 1/(K+1), as Phase 17 wrote down.
        assertThat(hits).isNotEmpty();
        assertThat(hits).anyMatch(hit -> hit.content().toLowerCase().contains("quicksort"));
    }

    @Test
    void aQuestionWithNothingToMatchOnProducesNoListRatherThanEveryChunk() throws Exception {
        Fixture fixture = ingest();

        // A follow-up in a thread, which is answered from the conversation's history. Every word is
        // scaffolding or under the length floor, so there is no term to scan on — and the honest
        // answer to that is an empty list rather than a `where true` returning the whole course.
        assertThat(trigramStage.search(fixture.courseId, fixture.actorId, "what about that one?"))
                .isEmpty();
    }

    @Test
    void theFuzzyListCannotSeeSomebodyElsesPrivateNote() throws Exception {
        Fixture fixture = ingest();
        // The visibility clause is copied into this query from the three beside it, and a copied
        // clause is exactly the one worth a test: it is the only place a private note could reach
        // a stranger, and nothing downstream re-checks it.
        Document document = documentRepository.findById(fixture.documentId).orElseThrow();
        document.setVisibility(DocumentVisibility.OWNER);
        documentRepository.saveAndFlush(document);

        List<String> terms = QueryTerms.of(TYPO_QUERY, 8);
        assertThat(searchRepository.trigramSearch(fixture.courseId, fixture.actorId, terms, 20))
                .as("its own uploader can still find it")
                .isNotEmpty();
        assertThat(searchRepository.trigramSearch(fixture.courseId, UUID.randomUUID(), terms, 20))
                .as("nobody else can")
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private record Fixture(UUID actorId, UUID courseId, UUID documentId) { }

    private Fixture ingest() throws Exception {
        User actor = saveUser();
        String token = jwtService.generateAccessToken(actor);
        UUID courseId = UUID.fromString(createCourse(token));

        // TWO_COLUMN is the fixture with real vocabulary in it — quicksort, heapsort, radix sort,
        // a decision-tree bound — rather than one sentence repeated down the page.
        byte[] pdf = TestPdfs.of(Kind.TWO_COLUMN, Kind.PROSE);
        UUID documentId = upload(courseId, token,
                new MockMultipartFile("file", "sorting.pdf", "application/pdf", pdf));
        ingestionService.ingest(documentId);

        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.READY);
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
        user.setEmail("trigram-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Trigram Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Trigram retrieval", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
