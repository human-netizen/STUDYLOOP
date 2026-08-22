package com.studyloop.backend.document;

import com.studyloop.backend.document.DocumentService.DocumentContent;
import com.studyloop.backend.document.DocumentService.UploadOutcome;
import com.studyloop.backend.document.dto.DocumentResponse;
import com.studyloop.backend.document.dto.DocumentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/courses/{courseId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentSummaryService summaryService;

    // Upload a course document for ingestion. A new file → 202 Accepted (the pipeline runs
    // asynchronously); an already-ingested identical file → 200 OK with the existing record.
    // The returned id is the job handle to poll via GET /{documentId}.
    @PostMapping
    public ResponseEntity<DocumentResponse> upload(Authentication authentication,
                                                   @PathVariable UUID courseId,
                                                   @RequestParam("file") MultipartFile file) {
        UploadOutcome outcome = documentService.upload(
                UUID.fromString(authentication.getName()), courseId, file);
        HttpStatus status = outcome.created() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.document());
    }

    // Any course member may see the course's documents and their ingestion status.
    @GetMapping
    public List<DocumentResponse> list(Authentication authentication, @PathVariable UUID courseId) {
        return documentService.list(UUID.fromString(authentication.getName()), courseId);
    }

    @GetMapping("/{documentId}")
    public DocumentResponse getOne(Authentication authentication,
                                   @PathVariable UUID courseId,
                                   @PathVariable UUID documentId) {
        return documentService.getOne(UUID.fromString(authentication.getName()), courseId, documentId);
    }

    // The cached AI summary + glossary (Phase 8.2). Cheap: it reads what ingestion already
    // generated and never calls the model, so `summary` is null for a document that hasn't been
    // summarized yet — the client offers generation rather than treating that as an error.
    @GetMapping("/{documentId}/summary")
    public DocumentSummaryResponse summary(Authentication authentication,
                                           @PathVariable UUID courseId,
                                           @PathVariable UUID documentId) {
        return summaryService.get(UUID.fromString(authentication.getName()), courseId, documentId);
    }

    // Generates the summary on demand — for documents ingested before the feature existed, or
    // whose generation failed. Idempotent: returns the cached summary untouched unless
    // ?refresh=true, so a double-click costs one model call, not two.
    @PostMapping("/{documentId}/summary")
    public DocumentSummaryResponse generateSummary(Authentication authentication,
                                                   @PathVariable UUID courseId,
                                                   @PathVariable UUID documentId,
                                                   @RequestParam(defaultValue = "false") boolean refresh) {
        return summaryService.generateFor(
                UUID.fromString(authentication.getName()), courseId, documentId, refresh);
    }

    // Streams the stored bytes inline so the citation viewer can render the source. Served to any
    // course member; the browser fetches it with the bearer token and renders it client-side
    // (react-pdf), so it's returned inline rather than as an attachment.
    //
    // The document's own content type, not a hardcoded PDF one. Phase 16 made that a correctness
    // matter rather than a tidiness one: a .pptx served as `application/pdf` is a file the browser
    // hands to a PDF viewer that cannot open it, and the symptom is a blank pane rather than a
    // download.
    @GetMapping("/{documentId}/file")
    public ResponseEntity<byte[]> file(Authentication authentication,
                                       @PathVariable UUID courseId,
                                       @PathVariable UUID documentId) {
        DocumentContent content = documentService.getContent(
                UUID.fromString(authentication.getName()), courseId, documentId);
        return ResponseEntity.ok()
                .contentType(mediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.filename()).build().toString())
                .body(content.bytes());
    }

    private static MediaType mediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
