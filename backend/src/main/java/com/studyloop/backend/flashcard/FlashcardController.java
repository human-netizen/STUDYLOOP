package com.studyloop.backend.flashcard;

import com.studyloop.backend.flashcard.dto.CreateFlashcardRequest;
import com.studyloop.backend.flashcard.dto.FlashcardResponse;
import com.studyloop.backend.flashcard.dto.GenerateFlashcardsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    @DeleteMapping("/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication,
                       @PathVariable UUID courseId,
                       @PathVariable UUID cardId) {
        flashcardService.delete(UUID.fromString(authentication.getName()), courseId, cardId);
    }
}
