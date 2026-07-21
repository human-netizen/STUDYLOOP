package com.studyloop.backend.document;

import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.CourseSpace;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.document.dto.DocumentResponse;
import lombok.RequiredArgsConstructor;
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

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final int MAX_FILENAME_LENGTH = 255;

    private final DocumentRepository documentRepository;
    private final DocumentStorageService storageService;
    private final CourseAccess courseAccess;

    // Accepts a course material for ingestion. Manager-only (OWNER/INSTRUCTOR): a course's
    // corpus is curated, not crowd-sourced by every member. Re-uploading identical bytes is
    // idempotent — it returns the existing document rather than storing a second copy.
    @Transactional
    public UploadOutcome upload(UUID actorId, UUID courseId, MultipartFile file) {
        Membership actor = courseAccess.requireManager(actorId, courseId);

        if (file == null || file.isEmpty()) {
            throw new EmptyDocumentException();
        }
        if (!PDF_CONTENT_TYPE.equalsIgnoreCase(file.getContentType())) {
            throw new UnsupportedDocumentTypeException(file.getContentType());
        }

        byte[] bytes = readBytes(file);
        String sha256 = storageService.sha256Hex(bytes);

        Optional<Document> existing = documentRepository.findByCourseSpaceIdAndSha256(courseId, sha256);
        if (existing.isPresent()) {
            return new UploadOutcome(DocumentResponse.from(existing.get(), courseId), false);
        }

        String storagePath = storageService.store(courseId, sha256, bytes);

        CourseSpace course = actor.getCourseSpace();
        Document document = new Document();
        document.setCourseSpace(course);
        document.setUploadedBy(actor.getUser());
        document.setFilename(sanitizeFilename(file.getOriginalFilename()));
        document.setContentType(PDF_CONTENT_TYPE);
        document.setSizeBytes(bytes.length);
        document.setSha256(sha256);
        document.setStoragePath(storagePath);
        document.setStatus(DocumentStatus.UPLOADED);
        // Flush so the @CreationTimestamp/@UpdateTimestamp are populated before we respond.
        documentRepository.saveAndFlush(document);

        return new UploadOutcome(DocumentResponse.from(document, courseId), true);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        return documentRepository.findByCourseSpaceIdOrderByCreatedAtDesc(courseId)
                .stream()
                .map(document -> DocumentResponse.from(document, courseId))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse getOne(UUID actorId, UUID courseId, UUID documentId) {
        courseAccess.requireMember(actorId, courseId);
        Document document = documentRepository.findByIdAndCourseSpaceId(documentId, courseId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return DocumentResponse.from(document, courseId);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new DocumentStorageException("Could not read the uploaded file.", e);
        }
    }

    // Keeps only the base name (drops any client-supplied path), caps length, and falls back
    // to a generic name when the client sent none.
    private static String sanitizeFilename(String original) {
        String cleaned = StringUtils.getFilename(original);
        if (cleaned == null || cleaned.isBlank()) {
            return "document.pdf";
        }
        cleaned = cleaned.trim();
        return cleaned.length() > MAX_FILENAME_LENGTH
                ? cleaned.substring(0, MAX_FILENAME_LENGTH)
                : cleaned;
    }

    // Pairs the response with whether a new document was created (→ 202) or an existing one
    // was returned unchanged (→ 200), leaving the status choice to the controller.
    public record UploadOutcome(DocumentResponse document, boolean created) { }
}
