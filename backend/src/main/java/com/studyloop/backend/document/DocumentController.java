package com.studyloop.backend.document;

import com.studyloop.backend.document.DocumentImpactRepository.DocumentImpact;
import com.studyloop.backend.document.DocumentService.DocumentContent;
import com.studyloop.backend.document.DocumentService.UploadOutcome;
import com.studyloop.backend.document.dto.CourseOutline;
import com.studyloop.backend.document.dto.DocumentResponse;
import com.studyloop.backend.document.dto.DocumentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final DocumentLifecycleService lifecycleService;
    private final CourseOutlineService courseOutlineService;

    // Upload a course document for ingestion. A new file → 202 Accepted (the pipeline runs
    // asynchronously); an already-ingested identical file → 200 OK with the existing record.
    // The returned id is the job handle to poll via GET /{documentId}.
    //
    // Phase 25.1 added the optional page range. Two request params rather than a JSON body, because
    // the request is already `multipart/form-data` for the file and mixing a JSON part into it would
    // make every client build a multipart body by hand. Absent means the whole document, which is
    // every caller that existed before this phase.
    @PostMapping
    public ResponseEntity<DocumentResponse> upload(Authentication authentication,
                                                   @PathVariable UUID courseId,
                                                   @RequestParam("file") MultipartFile file,
                                                   @RequestParam(required = false) Integer firstPage,
                                                   @RequestParam(required = false) Integer lastPage) {
        UploadOutcome outcome = documentService.upload(
                UUID.fromString(authentication.getName()), courseId, file,
                PageRange.of(firstPage, lastPage));
        HttpStatus status = outcome.created() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.document());
    }

    // Phase 27.2 — read the stored bytes again, optionally over a different page range. Same two
    // params as the upload, and manager-only for the same reason: it spends the vision quota
    // exactly as an upload does.
    //
    // 202 rather than 200, and the same shape the upload returns: the pipeline runs asynchronously
    // and the client polls the row it already knows how to poll.
    @PostMapping("/{documentId}/reingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentResponse reingest(Authentication authentication,
                                     @PathVariable UUID courseId,
                                     @PathVariable UUID documentId,
                                     @RequestParam(required = false) Integer firstPage,
                                     @RequestParam(required = false) Integer lastPage) {
        return documentService.reingest(UUID.fromString(authentication.getName()), courseId,
                documentId, PageRange.of(firstPage, lastPage));
    }

    // Phase 27.3 — out of every answer, still in the library. POST rather than DELETE because
    // nothing is destroyed, and named verbs rather than a PATCH on `status` because the set of
    // legal transitions is two, not six: the notes endpoint already reads this way with
    // promote/demote.
    @PostMapping("/{documentId}/retire")
    public DocumentResponse retire(Authentication authentication,
                                   @PathVariable UUID courseId,
                                   @PathVariable UUID documentId) {
        return lifecycleService.retire(UUID.fromString(authentication.getName()), courseId, documentId);
    }

    @PostMapping("/{documentId}/unretire")
    public DocumentResponse unretire(Authentication authentication,
                                     @PathVariable UUID courseId,
                                     @PathVariable UUID documentId) {
        return lifecycleService.unretire(UUID.fromString(authentication.getName()), courseId, documentId);
    }

    // What deleting this document would destroy, in counts. Fetched when the confirmation opens,
    // so the question a reader is asked is one they can actually answer.
    @GetMapping("/{documentId}/impact")
    public DocumentImpact impact(Authentication authentication,
                                 @PathVariable UUID courseId,
                                 @PathVariable UUID documentId) {
        return lifecycleService.impact(UUID.fromString(authentication.getName()), courseId, documentId);
    }

    // Gone. Answers with the impact rather than 204, because the counts are what the client puts
    // on screen afterwards — "deleted Lecture 07: 412 passages, 7 flashcards lost their source" —
    // and re-reading them after the delete is impossible by construction.
    @DeleteMapping("/{documentId}")
    public DocumentImpact delete(Authentication authentication,
                                 @PathVariable UUID courseId,
                                 @PathVariable UUID documentId) {
        return lifecycleService.delete(UUID.fromString(authentication.getName()), courseId, documentId);
    }

    // Any course member may see the course's documents and their ingestion status.
    @GetMapping
    public List<DocumentResponse> list(Authentication authentication, @PathVariable UUID courseId) {
        return documentService.list(UUID.fromString(authentication.getName()), courseId);
    }

    // Phase 28.4 — the same corpus as a table of contents: every document's sections, their page
    // spans and their glossary terms, and how many questions each document has ever answered.
    //
    // Under /documents rather than at a path of its own because it is a second view of exactly
    // what `list` returns, scoped by the same membership and the same visibility rule.
    @GetMapping("/outline")
    public CourseOutline outline(Authentication authentication, @PathVariable UUID courseId) {
        return courseOutlineService.outline(UUID.fromString(authentication.getName()), courseId);
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
