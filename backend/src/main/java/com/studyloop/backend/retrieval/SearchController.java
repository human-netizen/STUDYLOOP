package com.studyloop.backend.retrieval;

import com.studyloop.backend.retrieval.dto.SearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Search a course's materials. Sits beside /retrieve rather than replacing it: /retrieve returns
// raw chunks and exists to eyeball the retriever, this returns grouped, windowed passages meant
// for a person. A GET with the query in `q` so a result page can be linked and reloaded.
@RestController
@RequestMapping("/api/v1/courses/{courseId}/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public SearchResponse search(Authentication authentication,
                                 @PathVariable UUID courseId,
                                 @RequestParam(value = "q", defaultValue = "") String query,
                                 @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return searchService.search(UUID.fromString(authentication.getName()), courseId, query, limit);
    }
}
