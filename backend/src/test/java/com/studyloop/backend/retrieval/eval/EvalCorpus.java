package com.studyloop.backend.retrieval.eval;

import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRepository;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentChunkRepository;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.DocumentRepository;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.DocumentStorageService;
import com.studyloop.backend.document.SyntheticQueryGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Builds the course the eval measures against, out of the committed fixture PDFs.
//
// The harness this replaces read a course id and an actor id out of a JSON file, both of them rows
// somebody had created by hand in the development database months earlier. That made the numbers
// unreproducible (nobody else had those rows), unrepeatable (the course drifted every time a
// document was uploaded to it while demoing), and unrunnable in CI. Worse, it aimed the eval at the
// database the application runs against, which is the one thing the Phase 10.1 test database exists
// to prevent.
//
// So this seeds its own everything: its own user, its own course, its own fourteen documents.
//
// Seeding is idempotent and the corpus is left in place afterwards rather than torn down. Ingesting
// the corpus costs fourteen extractions and ~282 chunk embeddings; a phase that re-runs the eval
// four times while tuning a threshold should pay that once, not four times. Nothing else in the
// suite can see this course — it has its own owner and no other member — and `reset()` rebuilds it
// from scratch when the pipeline that produced the chunks has itself changed, which is exactly what
// Phase 13 will need on the day it rewrites chunking.
//
// Not a @Component: a stereotype annotation in the test tree is on the classpath for the whole
// suite, so every @SpringBootTest in the project would build one of these. EvalCorpusConfig hands
// it to the one test that wants it.
@RequiredArgsConstructor
public class EvalCorpus {

    // Bracketed so it sorts and reads as machinery rather than as somebody's course.
    public static final String COURSE_NAME = "[eval] Open Data Structures";
    public static final String OWNER_EMAIL = "eval-harness@studyloop.invalid";

    private final UserRepository userRepository;
    private final CourseSpaceRepository courseSpaceRepository;
    private final MembershipRepository membershipRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentStorageService storageService;
    private final DocumentIngestionService ingestionService;
    private final JdbcTemplate jdbcTemplate;

    // What the harness needs to run, plus what the report needs to say about the corpus it ran
    // against. `ingested` vs `reused` is the line that explains a slow run, and a run that ingested
    // nothing is the cheap case this class exists to make possible.
    public record Seeded(UUID courseId, UUID actorId, int documents, int chunks, int ingested,
                         int reused, int synthetic, int pages, int visionPages) {

        public boolean rebuilt() {
            return ingested > 0;
        }
    }

    public Seeded ensureSeeded() {
        User owner = ensureOwner();
        CourseSpace course = ensureCourse(owner);

        int ingested = 0;
        int reused = 0;
        for (FixtureDocument fixture : FixtureDocument.values()) {
            if (ensureDocument(course, owner, fixture)) {
                ingested++;
            } else {
                reused++;
            }
        }

        int chunks = 0;
        for (Document document : documentRepository.findByCourseSpaceIdOrderByCreatedAtDesc(course.getId())) {
            chunks += (int) chunkRepository.countByDocumentId(document.getId());
        }
        return new Seeded(course.getId(), owner.getId(),
                FixtureDocument.values().length, chunks, ingested, reused,
                syntheticChunks(course.getId()),
                sum(course.getId(), "page_count"), sum(course.getId(), "vision_pages"));
    }

    // Phase 15.4 — how much of the corpus a vision model read, straight out of the corpus.
    //
    // The instrument Phase 14 had to improvise, made ordinary. There the fact lived only inside a
    // string, so the report had to go looking for a marker in embed_text to avoid describing a
    // pipeline the corpus was not built with; here the ingest writes it down. Zero is a real
    // reading and the one this eval usually prints: with no vision key the router scores every
    // page and sends none, which is a different corpus from one where twenty-one pages were read
    // and the report has to be able to tell them apart.
    private int sum(UUID courseId, String column) {
        Integer total = jdbcTemplate.queryForObject(
                "select coalesce(sum(coalesce(" + column + ", 0)), 0) from documents "
                        + "where course_space_id = ?", Integer.class, courseId);
        return total == null ? 0 : total;
    }

    // How many of the corpus's chunks carry a Phase 14 synthetic-query block, read out of the text
    // itself rather than out of a column written to record it.
    //
    // It exists because the synthetic-queries flag is the one stage flag consumed at *ingest*: the
    // questions are inside the string that was embedded, so flipping the property changes nothing
    // about a corpus already in the database. Without this the easiest mistake in the phase — turn
    // the flag on, forget `-Deval.reset=true`, read a report headed synthetic-queries=ON that
    // describes the Phase 13 corpus — produces a number rather than an error. Same family as the
    // rerank-fallback count: a stage that is invisible by construction gets an instrument.
    private int syntheticChunks(UUID courseId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from document_chunks c
                join documents d on d.id = c.document_id
                where d.course_space_id = ?
                  and c.embed_text like ?
                """, Integer.class, courseId, "%" + SyntheticQueryGenerator.MARKER + "%");
        return count == null ? 0 : count;
    }

    // Drops the seeded course outright. Every table that references a course space cascades from
    // it, so one delete takes the documents, the chunks and their vectors with it; the owner is
    // left, since a user row costs nothing and re-creating it would only churn ids.
    //
    // Run when the ingestion pipeline changes shape — new chunker, new embedding model, new
    // extraction — because the corpus in the database was produced by the old one and comparing a
    // fresh run against it would attribute the pipeline change to whatever the phase actually did.
    public void reset() {
        findCourse().ifPresent(course -> jdbcTemplate.update(
                "delete from course_spaces where id = ?", course.getId()));
    }

    private User ensureOwner() {
        return userRepository.findByEmail(OWNER_EMAIL).orElseGet(() -> {
            User user = new User();
            user.setEmail(OWNER_EMAIL);
            // Never logged into. A BCrypt-shaped string nothing can produce a password for, rather
            // than a hash of some known string that would be a real account if the eval ever ran
            // against a database that mattered.
            user.setPasswordHash("$2a$10$" + "x".repeat(53));
            user.setDisplayName("Retrieval eval harness");
            user.setRole(Role.USER);
            return userRepository.saveAndFlush(user);
        });
    }

    private CourseSpace ensureCourse(User owner) {
        Optional<CourseSpace> existing = findCourse();
        if (existing.isPresent()) {
            return existing.get();
        }

        CourseSpace course = new CourseSpace();
        course.setName(COURSE_NAME);
        course.setDescription("Fixture corpus for the retrieval eval — Pat Morin, Open Data "
                + "Structures (CC BY 2.5 CA). Seeded and owned by the harness; not a real course.");
        course.setOwner(owner);
        CourseSpace saved = courseSpaceRepository.saveAndFlush(course);

        // Retrieval is member-scoped, so the harness has to be a member of the course it queries.
        Membership membership = new Membership();
        membership.setCourseSpace(saved);
        membership.setUser(owner);
        membership.setRole(MembershipRole.OWNER);
        membershipRepository.saveAndFlush(membership);
        return saved;
    }

    private Optional<CourseSpace> findCourse() {
        List<UUID> ids = jdbcTemplate.queryForList(
                "select id from course_spaces where name = ? order by created_at limit 1",
                UUID.class, COURSE_NAME);
        return ids.isEmpty() ? Optional.empty() : courseSpaceRepository.findById(ids.get(0));
    }

    // True if this call ingested the document, false if a previous run already had.
    //
    // Deliberately not the upload endpoint. That path publishes an AFTER_COMMIT event and the real
    // pipeline runs asynchronously, so seeding through it would mean polling fourteen documents for
    // READY, with fourteen extractions racing each other into the same Cohere account. Driving
    // ingest() directly keeps the run sequential and deterministic while still exercising the real
    // extract → chunk → embed pipeline, which is the part the retrieval numbers depend on. The
    // upload endpoint itself is covered by DocumentUploadTest.
    private boolean ensureDocument(CourseSpace course, User owner, FixtureDocument fixture) {
        byte[] bytes = fixture.bytes();
        String sha256 = storageService.sha256Hex(bytes);

        Optional<Document> existing =
                documentRepository.findByCourseSpaceIdAndSha256(course.getId(), sha256);
        if (existing.isPresent() && existing.get().getStatus() == DocumentStatus.READY
                && chunkRepository.countByDocumentId(existing.get().getId()) > 0) {
            return false;
        }

        Document document = existing.orElseGet(Document::new);
        document.setCourseSpace(course);
        document.setUploadedBy(owner);
        document.setFilename(fixture.fileName());
        document.setContentType("application/pdf");
        document.setSizeBytes(bytes.length);
        document.setSha256(sha256);
        document.setStoragePath(storageService.store(course.getId(), sha256, bytes));
        document.setStatus(DocumentStatus.UPLOADED);
        Document saved = documentRepository.saveAndFlush(document);

        ingestionService.ingest(saved.getId());

        // ingest() records a failure on the document rather than throwing, so an unread fixture
        // would otherwise surface as a mysteriously empty corpus and a page of zeroes.
        Document after = documentRepository.findById(saved.getId()).orElseThrow();
        if (after.getStatus() != DocumentStatus.READY) {
            throw new IllegalStateException("Fixture " + fixture.fileName() + " failed to ingest: "
                    + after.getErrorMessage());
        }
        return true;
    }

    // Every document in the seeded course that isn't one of the fourteen fixtures. Anything here
    // is corpus the golden set never accounted for, which would quietly change what retrieval is
    // choosing between — so the harness reports it rather than scoring around it.
    public List<String> strayDocuments(UUID courseId) {
        List<String> strays = new ArrayList<>();
        for (Document document : documentRepository.findByCourseSpaceIdOrderByCreatedAtDesc(courseId)) {
            if (FixtureDocument.byFileName(document.getFilename()) == null) {
                strays.add(document.getFilename());
            }
        }
        return strays;
    }
}
