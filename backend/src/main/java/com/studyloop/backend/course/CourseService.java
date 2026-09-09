package com.studyloop.backend.course;

import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserNotFoundException;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.common.PageResponse;
import com.studyloop.backend.course.dto.CourseResponse;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.course.dto.MemberResponse;
import com.studyloop.backend.course.dto.UpdateCourseRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseSpaceRepository courseSpaceRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CourseAccess courseAccess;
    private final Clock clock;

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
    public PageResponse<CourseResponse> listMine(UUID userId, boolean includeArchived,
                                                 Pageable pageable) {
        Page<CourseResponse> page = membershipRepository
                .findByUserIdWithCourse(userId, includeArchived, pageable)
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

    // -- Phase 27.4: the verbs twenty-six phases never added ----------------------------------

    // The first PATCH in this codebase.
    //
    // Owner-only, which is stricter than uploading material. An INSTRUCTOR curates what is *in* a
    // course; the course's own name is what every member sees in their list and what an invite
    // email names, and renaming it out from under them is the owner's call.
    //
    // Null means "leave it alone", so a rename sends one field. Blank is a different thing from
    // absent, and the two fields treat it differently: a blank name is refused (the schema says
    // not null and the create path says not blank), a blank description clears it, because
    // clearing one is a real intent with no other way to express it.
    @Transactional
    public CourseResponse update(UUID actorId, UUID courseId, UpdateCourseRequest request) {
        Membership actor = requireOwner(actorId, courseId);
        if (request.isEmpty()) {
            throw new InvalidCourseUpdateException("Send a name or a description to change.");
        }
        CourseSpace course = actor.getCourseSpace();
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new InvalidCourseUpdateException("A course needs a name.");
            }
            course.setName(request.name().strip());
        }
        if (request.description() != null) {
            String described = request.description().strip();
            course.setDescription(described.isEmpty() ? null : described);
        }
        courseSpaceRepository.saveAndFlush(course);
        return CourseResponse.from(course, actor.getRole());
    }

    // Out of the list, nothing destroyed - the same idea as retiring a document, and the same
    // argument: a course that finished in June should be able to leave the page without anybody
    // having to decide whether to destroy a semester of material.
    //
    // Deliberately *not* a read block. An archived course still answers questions, still serves
    // its documents and still opens by direct link; it is hidden from the list and marked, and
    // that is the whole of it. Making archive also mean "read-only" would be a second feature
    // wearing the same word, and the first person to hit it would have no way to tell which one
    // they had got.
    @Transactional
    public CourseResponse archive(UUID actorId, UUID courseId) {
        return setArchived(actorId, courseId, clock.instant());
    }

    @Transactional
    public CourseResponse unarchive(UUID actorId, UUID courseId) {
        return setArchived(actorId, courseId, null);
    }

    private CourseResponse setArchived(UUID actorId, UUID courseId, Instant at) {
        Membership actor = requireOwner(actorId, courseId);
        CourseSpace course = actor.getCourseSpace();
        course.setArchivedAt(at);
        courseSpaceRepository.saveAndFlush(course);
        return CourseResponse.from(course, actor.getRole());
    }

    // Who is in this course. Any member may see it - a class list is not a secret from the class,
    // and hiding it would make "remove a member" an action against a name the manager had to know
    // some other way.
    @Transactional(readOnly = true)
    public List<MemberResponse> members(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        return membershipRepository.findByCourseId(courseId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    // A member removes their own membership. Their uploads, their forum answers and the questions
    // they asked all stay - those belong to the course, and Phase 20's provenance is built on
    // exactly that.
    @Transactional
    public void leave(UUID actorId, UUID courseId) {
        Membership membership = courseAccess.requireMember(actorId, courseId);
        removeMembership(membership, courseId);
    }

    // A manager removes somebody else's. An OWNER may only be removed by an OWNER: an INSTRUCTOR
    // removing the person who put them there is a privilege escalation dressed as an
    // administrative action.
    @Transactional
    public void removeMember(UUID actorId, UUID courseId, UUID memberId) {
        Membership actor = courseAccess.requireManager(actorId, courseId);
        if (actorId.equals(memberId)) {
            // Removing yourself here would skip nothing and read as a different action in the
            // log. Point at the endpoint that means it.
            throw new InvalidCourseUpdateException("Use the leave endpoint to remove yourself.");
        }
        Membership target = membershipRepository.findByCourseIdAndUserId(courseId, memberId)
                .orElseThrow(() -> new NotACourseMemberException(courseId));
        if (target.getRole() == MembershipRole.OWNER && actor.getRole() != MembershipRole.OWNER) {
            throw new InsufficientCourseRoleException(courseId);
        }
        removeMembership(target, courseId);
    }

    // The shared half, and the one rule that matters: the last OWNER cannot go, by either route.
    private void removeMembership(Membership membership, UUID courseId) {
        if (membership.getRole() == MembershipRole.OWNER
                && membershipRepository.countByCourseSpaceIdAndRole(
                        courseId, MembershipRole.OWNER) <= 1) {
            throw new LastOwnerException(courseId);
        }
        membershipRepository.delete(membership);
    }

    private Membership requireOwner(UUID actorId, UUID courseId) {
        Membership membership = courseAccess.requireMember(actorId, courseId);
        if (membership.getRole() != MembershipRole.OWNER) {
            throw new InsufficientCourseRoleException(courseId);
        }
        return membership;
    }
}
