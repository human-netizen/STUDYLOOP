package com.studyloop.backend.course;

import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserNotFoundException;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.common.PageResponse;
import com.studyloop.backend.course.dto.CourseResponse;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseSpaceRepository courseSpaceRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;

    // Creating a course makes the creator its OWNER in the same transaction, so a course
    // never exists without a membership.
    @Transactional
    public CourseResponse create(UUID ownerId, CreateCourseRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new UserNotFoundException(ownerId));

        CourseSpace course = new CourseSpace();
        course.setName(request.name());
        course.setDescription(request.description());
        course.setOwner(owner);
        // Flush so @CreationTimestamp is populated before we build the response.
        courseSpaceRepository.saveAndFlush(course);

        Membership membership = new Membership();
        membership.setCourseSpace(course);
        membership.setUser(owner);
        membership.setRole(MembershipRole.OWNER);
        membershipRepository.save(membership);

        return CourseResponse.from(course, MembershipRole.OWNER);
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseResponse> listMine(UUID userId, Pageable pageable) {
        Page<CourseResponse> page = membershipRepository.findByUserIdWithCourse(userId, pageable)
                .map(m -> CourseResponse.from(m.getCourseSpace(), m.getRole()));
        return PageResponse.of(page);
    }

    // A caller may only read a course they belong to. We tell "no such course" (404)
    // apart from "exists but not yours" (403) so members get honest errors while the
    // course's existence isn't leaked to strangers beyond that.
    @Transactional(readOnly = true)
    public CourseResponse getById(UUID userId, UUID courseId) {
        Membership membership = membershipRepository.findByCourseIdAndUserId(courseId, userId)
                .orElseThrow(() -> {
                    if (courseSpaceRepository.existsById(courseId)) {
                        return new NotACourseMemberException(courseId);
                    }
                    return new CourseNotFoundException(courseId);
                });
        return CourseResponse.from(membership.getCourseSpace(), membership.getRole());
    }
}
