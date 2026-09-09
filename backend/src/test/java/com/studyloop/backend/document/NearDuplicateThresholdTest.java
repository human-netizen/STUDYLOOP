package com.studyloop.backend.document;

import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.config.DuplicateProperties;
import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRepository;
import com.studyloop.backend.course.MembershipRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 29.2 — where the near-duplicate threshold goes, measured rather than chosen.
//
// **A threshold is only defensible against both answers.** It is easy to pick a number that
// catches a duplicate; the number that matters is the one that catches a duplicate *and* leaves
// two genuinely similar documents alone, and a run that only ever sees duplicates cannot tell you
// whether you have found a threshold or a floor. 12.2 and 15.1 were settled this way and so is
// this.
//
// The two answers, both from the committed fixture corpus — fourteen chapters of one textbook,
// which is the hardest available "similar but different":
//
//   duplicate   chapter 6 and a re-export of chapter 6 — the same lecture, different bytes
//   similar     chapter 6 and chapter 7 — consecutive chapters of the same book, same author,
//               same notation, subjects that build on each other
//
// **Real embeddings, so it stays out of CI.** The suite's stub keys a vector on the text, which
// puts the duplicate at 1.0 and the neighbouring chapter at ~0 and would make any threshold in
// between look perfect. That is the whole reason this is a separate class:
//
//     ./mvnw -Deval.duplicates=true -Dtest=NearDuplicateThresholdTest test
//
// It ingests four documents — two chapters, twice each — and costs that many embedding calls.
@SpringBootTest(properties = {
        // Four summary calls that nothing here reads.
        "studyloop.summary.auto-generate=false",
        // The detector's own write is irrelevant to the measurement: this test reads the raw
        // coverage from the detector rather than the verdict, because the verdict is the thing
        // being calibrated.
        "studyloop.duplicates.enabled=false"
})
@EnabledIfSystemProperty(named = "eval.duplicates", matches = "true")
class NearDuplicateThresholdTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CourseSpaceRepository courseSpaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentStorageService storageService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private NearDuplicateDetector detector;

    @Autowired
    private DuplicateProperties properties;

    // The separation the configured threshold has to sit inside. Not a quality target — a
    // statement that the two cases are distinguishable at all. If a re-export and the next chapter
    // score within this of each other, no threshold works and the metric is the thing to change.
    private static final double MIN_SEPARATION = 0.30;

    @Test
    void aReExportScoresFarAboveTheNextChapter() throws Exception {
        User owner = saveUser();
        UUID courseId = createCourse(owner, "Duplicate calibration");

        UUID chapter6 = ingest(courseId, owner, "06-binary-trees.pdf", "06-binary-trees.pdf");
        UUID chapter6Again = ingest(courseId, owner, "06-binary-trees (1).pdf", "06-binary-trees.pdf");
        UUID chapter7 = ingest(courseId, owner, "07-random-binary-search-trees.pdf",
                "07-random-binary-search-trees.pdf");

        NearDuplicateDetector.Match duplicate = detector.closestMatch(courseId, chapter6Again);
        NearDuplicateDetector.Match neighbour = detector.closestMatch(courseId, chapter7);

        System.out.printf("%n=== Phase 29.2 · near-duplicate calibration ===%n");
        System.out.printf("chunk floor        %.2f%n", properties.chunkFloor());
        System.out.printf("report threshold   %.2f%n", properties.reportThreshold());
        System.out.printf("re-export          coverage %.3f -> %s%n",
                coverage(duplicate), duplicate == null ? "(no match)" : duplicate.filename());
        System.out.printf("next chapter       coverage %.3f -> %s%n",
                coverage(neighbour), neighbour == null ? "(no match)" : neighbour.filename());
        System.out.printf("separation         %.3f%n%n", coverage(duplicate) - coverage(neighbour));

        // The re-export must be found, and found as the chapter it is a copy of rather than as
        // whichever document happens to rank first.
        assertThat(duplicate).isNotNull();
        assertThat(duplicate.documentId()).isEqualTo(chapter6);

        assertThat(coverage(duplicate) - coverage(neighbour))
                .as("a re-export and the next chapter must be distinguishable")
                .isGreaterThan(MIN_SEPARATION);

        // And the configured threshold has to be the line between them, not merely below one of
        // them. This is the assertion that fails when someone tunes the number by feel.
        assertThat(coverage(duplicate))
                .as("the configured threshold must report a re-export")
                .isGreaterThanOrEqualTo(properties.reportThreshold());
        assertThat(coverage(neighbour))
                .as("the configured threshold must leave the next chapter alone")
                .isLessThan(properties.reportThreshold());
    }

    private static double coverage(NearDuplicateDetector.Match match) {
        return match == null ? 0.0 : match.coverage();
    }

    // ── harness ───────────────────────────────────────────────────────────────────────────────

    // Stored and ingested directly rather than through the upload endpoint: the point here is the
    // vectors, and going through MockMvc would add a multipart round trip to say the same thing.
    // The filename differs from the resource on purpose in one case — that is what a second export
    // of the same chapter looks like in a downloads folder.
    private UUID ingest(UUID courseId, User owner, String filename, String resource) throws IOException {
        byte[] bytes = fixture(resource);
        String sha256 = storageService.sha256Hex(bytes);
        // A re-export is not the same bytes. Perturbing the hash is how this fixture stands in for
        // one without shipping a second copy of a 40-page chapter.
        String storagePath = storageService.store(courseId, sha256 + "-" + UUID.randomUUID(), bytes);

        Document document = new Document();
        document.setCourseSpace(courseSpaceRepository.getReferenceById(courseId));
        document.setUploadedBy(owner);
        document.setFilename(filename);
        document.setContentType("application/pdf");
        document.setSizeBytes(bytes.length);
        document.setSha256(UUID.randomUUID().toString());
        document.setStoragePath(storagePath);
        document.setStatus(DocumentStatus.UPLOADED);
        Document saved = documentRepository.saveAndFlush(document);

        ingestionService.ingest(saved.getId());
        return saved.getId();
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IOException("fixture not on the classpath: " + name);
            }
            return in.readAllBytes();
        }
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("dup-eval-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Duplicate Calibration");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private UUID createCourse(User owner, String name) {
        CourseSpace course = new CourseSpace();
        course.setName(name);
        course.setDescription("Phase 29.2 threshold calibration");
        course.setOwner(owner);
        CourseSpace saved = courseSpaceRepository.saveAndFlush(course);

        Membership membership = new Membership();
        membership.setCourseSpace(saved);
        membership.setUser(owner);
        membership.setRole(MembershipRole.OWNER);
        membershipRepository.saveAndFlush(membership);
        return saved.getId();
    }
}
