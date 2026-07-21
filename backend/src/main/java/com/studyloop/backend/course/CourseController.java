package com.studyloop.backend.course;

import com.studyloop.backend.common.PageResponse;
import com.studyloop.backend.course.dto.CourseResponse;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return courseService.listMine(UUID.fromString(authentication.getName()), pageable);
    }

    @GetMapping("/{id}")
    public CourseResponse getOne(Authentication authentication, @PathVariable UUID id) {
        return courseService.getById(UUID.fromString(authentication.getName()), id);
    }
}
