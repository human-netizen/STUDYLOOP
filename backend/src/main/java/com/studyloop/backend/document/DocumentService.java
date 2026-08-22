package com.studyloop.backend.document;

import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.document.dto.DocumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final int MAX_FILENAME_LENGTH = 255;

    private final DocumentRepository documentRepository;
    private final DocumentStorageService storageService;
    private final CourseAccess courseAccess;
    private final ApplicationEventPublisher eventPublisher;

    // Accepts a course material for ingestion. Manager-only (OWNER/INSTRUCTOR): a course's
    // corpus is curated, not crowd-sourced by every member. Re-uploading identical bytes is
    // idempotent — it returns the existing document rather than storing a second copy.
    //
    // Phase 16 widened what "a course material" may be from PDF alone to PDF, PowerPoint and Word.
    // Images are deliberately not on that list: a photograph is a personal note, it comes in
    // through the notes endpoint below with member permissions and owner-only visibility, and
    // letting one in here would give it manager permissions and course-wide visibility instead.
    @Transactional
    public UploadOutcome upload(UUID actorId, UUID courseId, MultipartFile file) {
        Membership actor = courseAccess.requireManager(actorId, courseId);
        return accept(actor, courseId, file, DocumentFormat.materialFormats(),
                DocumentSource.UPLOAD, DocumentVisibility.COURSE);
    }

    // The shared half of every upload: validate, hash, dedupe, store, save, fire the ingestion
    // event. What differs between a lecture PDF and a photographed note is the three arguments at
    // the end and the access check the caller already made — not the pipeline, which is the whole
    // reason a handwritten note is a first-class Document rather than a feature beside one.
    @Transactional
    UploadOutcome accept(Membership actor, UUID courseId, MultipartFile file,
                         List<DocumentFormat> allowed, DocumentSource source,
                         DocumentVisibility visibility) {
        if (file == null || file.isEmpty()) {
            throw new EmptyDocumentException();
        }
        DocumentFormat format = DocumentFormat.require(
                file.getContentType(), file.getOriginalFilename(), allowed);

        byte[] bytes = readBytes(file);
        String sha256 = storageService.sha256Hex(bytes);

        Optional<Document> existing = documentRepository.findByCourseSpaceIdAndSha256(courseId, sha256);
        if (existing.isPresent()) {
            return new UploadOutcome(
                    DocumentResponse.from(deduped(existing.get(), actor), courseId), false);
        }

        String storagePath = storageService.store(courseId, sha256, bytes);

        CourseSpace course = actor.getCourseSpace();
        Document document = new Document();
        document.setCourseSpace(course);
        document.setUploadedBy(actor.getUser());
        document.setFilename(sanitizeFilename(file.getOriginalFilename(), format));
        // The format's own type, not the one the client sent. A .pptx that arrived as
        // `application/octet-stream` is stored as OOXML, so both the extractor registry and the
        // download endpoint read a type that describes the bytes.
        document.setContentType(format.contentType());
        document.setSizeBytes(bytes.length);
        document.setSha256(sha256);
        document.setStoragePath(storagePath);
        document.setSource(source);
        document.setVisibility(visibility);
        document.setStatus(DocumentStatus.UPLOADED);
        // Flush so the @CreationTimestamp/@UpdateTimestamp are populated before we respond.
        documentRepository.saveAndFlush(document);

        // Kick off ingestion only after this transaction commits (the listener is bound to
        // AFTER_COMMIT), so the async worker always finds the row.
        eventPublisher.publishEvent(
                new DocumentUploadedEvent(document.getId(), actor.getUser().getId()));

        return new UploadOutcome(DocumentResponse.from(document, courseId), true);
    }

    // Deduping is per course, because `(course_space_id, sha256)` is a database guarantee — and
    // that becomes a disclosure the moment a document in a course is private to one member.
    // Uploading a file whose bytes match somebody else's note would otherwise hand back their
    // document: its id, its filename, and the timestamp they wrote it. Returning an *already
    // promoted* note is fine and true, since it is the course's material by then.
    private static Document deduped(Document existing, Membership actor) {
        boolean mine = existing.getUploadedBy().getId().equals(actor.getUser().getId());
        if (existing.getVisibility() == DocumentVisibility.OWNER && !mine) {
            throw new DuplicateDocumentException();
        }
        return existing;
    }

    // Uploaded material only. A course's corpus can also contain documents this system wrote from
    // accepted forum answers (Phase 9.2) and notes its members photographed (16.3) — they are
    // retrievable and citable, but no upload to *this* endpoint put them there, and a promoted
    // note appearing in the materials table would read as something a manager had uploaded. The
    // forum and the notes page show them where they belong.
    @Transactional(readOnly = true)
    public List<DocumentResponse> list(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        return documentRepository
                .findByCourseSpaceIdAndSourceOrderByCreatedAtDesc(courseId, DocumentSource.UPLOAD)
                .stream()
                .map(document -> DocumentResponse.from(document, courseId))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse getOne(UUID actorId, UUID courseId, UUID documentId) {
        courseAccess.requireMember(actorId, courseId);
        Document document = documentRepository.findVisibleById(documentId, courseId, actorId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return DocumentResponse.from(document, courseId);
    }

    // Loads the stored bytes so a member can view the source behind a citation. Any course member
    // may read the course's materials; the bytes come straight off disk by storage path.
    @Transactional(readOnly = true)
    public DocumentContent getContent(UUID actorId, UUID courseId, UUID documentId) {
        courseAccess.requireMember(actorId, courseId);
        Document document = documentRepository.findVisibleById(documentId, courseId, actorId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        // A forum-derived source has no file behind it. The client is not supposed to offer it as
        // one (citations carry their source), so this is the guard for a hand-typed URL rather
        // than a state the UI can reach — 404, because there is no file here to serve.
        if (document.getStoragePath() == null) {
            throw new DocumentNotFoundException(documentId);
        }
        byte[] bytes = storageService.read(document.getStoragePath());
        return new DocumentContent(bytes, document.getFilename(), document.getContentType());
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new DocumentStorageException("Could not read the uploaded file.", e);
        }
    }

    // Keeps only the base name (drops any client-supplied path), caps length, and falls back
    // to a generic name — with the right extension — when the client sent none.
    private static String sanitizeFilename(String original, DocumentFormat format) {
        String cleaned = StringUtils.getFilename(original);
        if (cleaned == null || cleaned.isBlank()) {
            return "document." + format.name().toLowerCase();
        }
        cleaned = cleaned.trim();
        return cleaned.length() > MAX_FILENAME_LENGTH
                ? cleaned.substring(0, MAX_FILENAME_LENGTH)
                : cleaned;
    }

    // Pairs the response with whether a new document was created (→ 202) or an existing one
    // was returned unchanged (→ 200), leaving the status choice to the controller.
    public record UploadOutcome(DocumentResponse document, boolean created) { }

    // Raw stored bytes plus the metadata the controller needs to set download headers.
    public record DocumentContent(byte[] bytes, String filename, String contentType) { }
}
