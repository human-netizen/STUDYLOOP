package com.studyloop.backend.course;

import com.studyloop.backend.common.PageResponse;
import com.studyloop.backend.course.dto.CourseResponse;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.course.dto.MemberResponse;
import com.studyloop.backend.course.dto.UpdateCourseRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    // The JWT filter stores the user's id as the Authentication name (see UserController).
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse create(Authentication authentication,
                                 @Valid @RequestBody CreateCourseRequest request) {
        return courseService.create(UUID.fromString(authentication.getName()), request);
    }

    // Lists only the courses the caller belongs to. Newest membership first by default;
    // override with ?page=&size=&sort=.
    @GetMapping
    public PageResponse<CourseResponse> listMine(
            Authentication authentication,
            // Phase 27.4. Archived courses are out of the list by default, which is the point
            // of archiving one; ?archived=true is how they are found again.
            @RequestParam(name = "archived", defaultValue = "false") boolean includeArchived,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return courseService.listMine(
                UUID.fromString(authentication.getName()), includeArchived, pageable);
    }

    @GetMapping("/{id}")
    public CourseResponse getOne(Authentication authentication, @PathVariable UUID id) {
        return courseService.getById(UUID.fromString(authentication.getName()), id);
    }

    // -- Phase 27.4 ---------------------------------------------------------------------------

    // The first PATCH in this codebase. PATCH rather than PUT because the body carries only what
    // changes: a client renaming a course sends `{"name": "..."}` and is not made responsible for
    // round-tripping a description it never read. Owner-only.
    @PatchMapping("/{id}")
    public CourseResponse update(Authentication authentication,
                                 @PathVariable UUID id,
                                 @Valid @RequestBody UpdateCourseRequest request) {
        return courseService.update(UUID.fromString(authentication.getName()), id, request);
    }

    // Out of the list, nothing destroyed. POST rather than DELETE for the same reason a document
    // is retired rather than deleted: nothing goes away, and a verb that says otherwise would be
    // the second-worst thing about the feature.
    @PostMapping("/{id}/archive")
    public CourseResponse archive(Authentication authentication, @PathVariable UUID id) {
        return courseService.archive(UUID.fromString(authentication.getName()), id);
    }

    @PostMapping("/{id}/unarchive")
    public CourseResponse unarchive(Authentication authentication, @PathVariable UUID id) {
        return courseService.unarchive(UUID.fromString(authentication.getName()), id);
    }

    @GetMapping("/{id}/members")
    public List<MemberResponse> members(Authentication authentication, @PathVariable UUID id) {
        return courseService.members(UUID.fromString(authentication.getName()), id);
    }

    // Leaving is a delete of your own membership, which is why it is a DELETE on a path that names
    // no user: the only membership this endpoint can touch is the caller's, and an id in the path
    // would be an id somebody could change.
    @DeleteMapping("/{id}/membership")
    public ResponseEntity<Void> leave(Authentication authentication, @PathVariable UUID id) {
        courseService.leave(UUID.fromString(authentication.getName()), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Void> removeMember(Authentication authentication,
                                             @PathVariable UUID id,
                                             @PathVariable UUID memberId) {
        courseService.removeMember(UUID.fromString(authentication.getName()), id, memberId);
        return ResponseEntity.noContent().build();
    }
}
