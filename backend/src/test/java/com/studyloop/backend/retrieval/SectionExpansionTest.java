package com.studyloop.backend.retrieval;

import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRepository;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentChunk;
import com.studyloop.backend.document.DocumentChunkRepository;
import com.studyloop.backend.document.DocumentRepository;
import com.studyloop.backend.document.ChunkModality;
import com.studyloop.backend.document.DocumentSource;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.StubAiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 13.5 — small-to-big: retrieval picks chunks, the prompt gets sections.
//
// Chunks are written straight to the repository rather than ingested from a PDF, because what is
// under test is the expansion, and building a PDF whose sections land where this test needs them
// would put the extractor's heuristics between the assertion and the thing it asserts.
//
// Annotations match SearchTest exactly so the two share one cached ApplicationContext — Supabase's
// session pooler caps clients at 15, and every distinct test configuration opens its own pool.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class SectionExpansionTest {

    private static final String SECTION = "Skiplists > 4.4 Analysis";

    @Autowired
    private SectionExpander expander;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    private Document document;
    private List<DocumentChunk> section;

    @BeforeEach
    void seedOneSectionOfThreeChunks() {
        User owner = new User();
        owner.setEmail("expander-" + UUID.randomUUID() + "@example.com");
        owner.setPasswordHash("test-hash");
        owner.setDisplayName("Owner");
        owner.setRole(Role.USER);
        userRepository.saveAndFlush(owner);

        CourseSpace course = new CourseSpace();
        course.setName("Algorithms");
        course.setOwner(owner);
        courseSpaceRepository.saveAndFlush(course);

        Membership membership = new Membership();
        membership.setCourseSpace(course);
        membership.setUser(owner);
        membership.setRole(MembershipRole.OWNER);
        membershipRepository.saveAndFlush(membership);

        document = new Document();
        document.setCourseSpace(course);
        document.setUploadedBy(owner);
        document.setFilename("04-skiplists.pdf");
        document.setContentType("application/pdf");
        document.setSizeBytes(1);
        document.setSha256(UUID.randomUUID().toString().replace("-", ""));
        document.setStoragePath("test/" + UUID.randomUUID());
        document.setStatus(DocumentStatus.READY);
        documentRepository.saveAndFlush(document);

        section = new ArrayList<>();
        section.add(chunk(0, SECTION, "The height of a skiplist is logarithmic in expectation."));
        section.add(chunk(1, SECTION, "The expected search time follows from that height."));
        section.add(chunk(2, SECTION, "The bound holds for every operation the structure supports."));
        // A different section of the same document, which must never be pulled in.
        chunk(3, "Skiplists > 4.5 Exercises", "Prove the bound for the randomized variant.");
        chunkRepository.flush();
    }

    private DocumentChunk chunk(int index, String sectionPath, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(document);
        chunk.setChunkIndex(index);
        chunk.setPageNumber(index + 1);
        chunk.setPageEnd(index + 1);
        chunk.setSectionPath(sectionPath);
        chunk.setContent(content);
        chunk.setTokenCount(12);
        return chunkRepository.saveAndFlush(chunk);
    }

    private RetrievedChunk retrieved(DocumentChunk chunk) {
        return new RetrievedChunk(chunk.getId(), document.getId(), document.getFilename(),
                DocumentSource.UPLOAD, chunk.getPageNumber(), chunk.getPageEnd(),
                chunk.getSectionPath(), chunk.getContent(), chunk.getTokenCount(),
                ChunkModality.TEXT, 0.01, 0.5, 0.9);
    }

    @Test
    void aHitIsHandedToTheModelWithTheRestOfItsSection() {
        List<String> expanded = expander.expand(List.of(retrieved(section.get(1))));

        // Retrieval matched the middle chunk on precision; the model reads the paragraph before and
        // after it, which is where a qualification of the claim usually sits.
        assertThat(expanded).hasSize(1);
        assertThat(expanded.get(0))
                .contains("The height of a skiplist")
                .contains("The expected search time")
                .contains("The bound holds for every operation");
    }

    @Test
    void expansionStopsAtTheSectionItStartedIn() {
        String expanded = expander.expand(List.of(retrieved(section.get(2)))).get(0);

        // The next chunk in the document is the next section. Crossing into it would hand the model
        // an exercise as if it were part of the analysis it is answering from.
        assertThat(expanded).doesNotContain("Prove the bound");
    }

    @Test
    void aChunkWithNoSectionExpandsToItself() {
        // Everything ingested before Phase 13 has a null section_path, and so does an accepted forum
        // answer. Neither is an error; there is simply nothing to expand to.
        DocumentChunk orphan = chunk(4, null, "An answer written back from the course forum.");
        String expanded = expander.expand(List.of(retrieved(orphan))).get(0);

        assertThat(expanded).isEqualTo("An answer written back from the course forum.");
    }

    @Test
    void twoHitsInOneSectionEachGetTheSectionAndTheOrderIsKept() {
        List<String> expanded = expander.expand(
                List.of(retrieved(section.get(2)), retrieved(section.get(0))));

        // One entry per retrieved chunk, in the order retrieval ranked them — the prompt numbers
        // its sources [1]..[6] and the citations are built from the same list.
        assertThat(expanded).hasSize(2);
        assertThat(expanded.get(0)).isEqualTo(expanded.get(1));
    }

    @Test
    void theSectionIsAssembledInDocumentOrderNotInRetrievalOrder() {
        String expanded = expander.expand(List.of(retrieved(section.get(2)))).get(0);

        assertThat(expanded.indexOf("The height of a skiplist"))
                .isLessThan(expanded.indexOf("The expected search time"));
        assertThat(expanded.indexOf("The expected search time"))
                .isLessThan(expanded.indexOf("The bound holds"));
    }
}
