package com.studyloop.backend.flashcard;

import com.studyloop.backend.flashcard.dto.CreateFlashcardRequest;
import com.studyloop.backend.flashcard.dto.FlashcardResponse;
import com.studyloop.backend.flashcard.dto.GenerateFlashcardsRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

// Personal flashcards within a course. Any member may generate a deck from a document, save a
// single card by hand (e.g. from a chat answer), list their own cards, and delete one. Cards are
// private to their creator — the list and delete are always scoped to the caller.
@RestController
@RequestMapping("/api/v1/courses/{courseId}/flashcards")
@RequiredArgsConstructor
public class FlashcardController {

    private final FlashcardService flashcardService;

    // Generate a deck from one READY document.
    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<FlashcardResponse> generate(Authentication authentication,
                                            @PathVariable UUID courseId,
                                            @Valid @RequestBody GenerateFlashcardsRequest request) {
        return flashcardService.generate(UUID.fromString(authentication.getName()), courseId, request);
    }

    // Save one card by hand.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlashcardResponse create(Authentication authentication,
                                    @PathVariable UUID courseId,
                                    @Valid @RequestBody CreateFlashcardRequest request) {
        return flashcardService.create(UUID.fromString(authentication.getName()), courseId, request);
    }

    @GetMapping
    public List<FlashcardResponse> list(Authentication authentication, @PathVariable UUID courseId) {
        return flashcardService.list(UUID.fromString(authentication.getName()), courseId);
    }

    // Phase 29.1. The same deck as a file the reader can take elsewhere. `ResponseEntity<byte[]>`
    // with the content type set is what takes this out of Jackson's hands — every other method
    // here returns an object and lets the converter chain pick JSON, and this one must not.
    //
    // The filename is a fallback: the browser saves under the name the client's download
    // attribute gives it, which is the course's, and this one is for whoever calls the endpoint
    // with curl.
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(Authentication authentication, @PathVariable UUID courseId) {
        String csv = flashcardService.exportCsv(UUID.fromString(authentication.getName()), courseId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("flashcards-" + courseId + ".csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @DeleteMapping("/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication,
                       @PathVariable UUID courseId,
                       @PathVariable UUID cardId) {
        flashcardService.delete(UUID.fromString(authentication.getName()), courseId, cardId);
    }
}
