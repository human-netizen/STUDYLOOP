package com.studyloop.backend.course;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Shared course-authorization guard. Resolves the caller's membership and enforces the
// 404-vs-403 split used across course features: a missing course is 404, while a course
// that exists but isn't the caller's is 403 (so existence isn't leaked to strangers).
// requireManager additionally rejects plain MEMBERs. Mirrors the logic CourseService and
// InviteService grew independently, so new features (documents, ...) reuse one copy.
@Component
@RequiredArgsConstructor
public class CourseAccess {

    private final MembershipRepository membershipRepository;
    private final CourseSpaceRepository courseSpaceRepository;

    // The membership returned join-fetches the course and its owner (see the repository),
    // so callers can read course fields without tripping a lazy load.
    public Membership requireMember(UUID actorId, UUID courseId) {
        return membershipRepository.findByCourseIdAndUserId(courseId, actorId)
                .orElseThrow(() -> {
                    if (courseSpaceRepository.existsById(courseId)) {
                        return new NotACourseMemberException(courseId);
                    }
                    return new CourseNotFoundException(courseId);
                });
    }

    public Membership requireManager(UUID actorId, UUID courseId) {
        Membership membership = requireMember(actorId, courseId);
        if (membership.getRole() == MembershipRole.MEMBER) {
            throw new InsufficientCourseRoleException(courseId);
        }
        return membership;
    }
}
