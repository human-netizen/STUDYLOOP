package com.studyloop.backend.document;

import com.studyloop.backend.document.DocumentService.UploadOutcome;
import com.studyloop.backend.document.dto.DocumentResponse;
import com.studyloop.backend.document.dto.NoteBlockResponse;
import com.studyloop.backend.document.dto.NoteResponse;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

// Digitised handwritten notes (Phase 16.3).
//
// A separate controller from DocumentController, on a separate path, because the two differ on
// every axis that matters at the web layer: who may post (any member, versus a manager), what is
// accepted (an image, versus PDF/PPTX/DOCX), who may read the result (its owner, versus the
// course), and what the extra verbs are (review, promote, export). One controller with a boolean
// would put four different access rules in one method.
@RestController
@RequestMapping("/api/v1/courses/{courseId}/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    // Photograph in, job handle out. 202 while the vision model reads it; poll the note's status
    // through the list, the same way an uploaded document is polled.
    @PostMapping
    public ResponseEntity<DocumentResponse> upload(Authentication authentication,
                                                   @PathVariable UUID courseId,
                                                   @RequestParam("file") MultipartFile file) {
        UploadOutcome outcome = noteService.upload(
                UUID.fromString(authentication.getName()), courseId, file);
        HttpStatus status = outcome.created() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.document());
    }

    // The caller's own notes, plus every note this course has promoted to material.
    @GetMapping
    public List<NoteResponse> list(Authentication authentication, @PathVariable UUID courseId) {
        return noteService.list(UUID.fromString(authentication.getName()), courseId);
    }

    // The review view: every block the model read, with its confidence and whether it was indexed.
    @GetMapping("/{noteId}/blocks")
    public List<NoteBlockResponse> blocks(Authentication authentication,
                                          @PathVariable UUID courseId,
                                          @PathVariable UUID noteId) {
        return noteService.blocks(UUID.fromString(authentication.getName()), courseId, noteId);
    }

    // Manager-gated: promoting writes member-authored text into the corpus every future answer may
    // be grounded on and cite, which is the same act as accepting a forum answer (9.2).
    @PostMapping("/{noteId}/promote")
    public NoteResponse promote(Authentication authentication,
                                @PathVariable UUID courseId,
                                @PathVariable UUID noteId) {
        return noteService.promote(UUID.fromString(authentication.getName()), courseId, noteId);
    }

    @PostMapping("/{noteId}/demote")
    public NoteResponse demote(Authentication authentication,
                               @PathVariable UUID courseId,
                               @PathVariable UUID noteId) {
        return noteService.demote(UUID.fromString(authentication.getName()), courseId, noteId);
    }

    // The note as a LaTeX document. An attachment rather than inline: this is the one thing here
    // meant to leave the system and be compiled somewhere else.
    @GetMapping("/{noteId}/latex")
    public ResponseEntity<byte[]> latex(Authentication authentication,
                                        @PathVariable UUID courseId,
                                        @PathVariable UUID noteId) {
        String tex = noteService.latex(UUID.fromString(authentication.getName()), courseId, noteId);
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "x-tex", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(noteId + ".tex").build().toString())
                .body(tex.getBytes(StandardCharsets.UTF_8));
    }
}
