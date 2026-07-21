package com.studyloop.backend.document;

import com.studyloop.backend.document.DocumentService.UploadOutcome;
import com.studyloop.backend.document.dto.DocumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
}
