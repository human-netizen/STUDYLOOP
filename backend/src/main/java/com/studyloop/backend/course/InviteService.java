package com.studyloop.backend.course;

import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserNotFoundException;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CourseResponse;
import com.studyloop.backend.course.dto.CreateInviteRequest;
import com.studyloop.backend.course.dto.InvitePreviewResponse;
import com.studyloop.backend.course.dto.InviteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InviteService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final CourseInviteRepository inviteRepository;
    private final MembershipRepository membershipRepository;
    private final CourseSpaceRepository courseSpaceRepository;
    private final UserRepository userRepository;

    // Issue an invite. Only OWNER/INSTRUCTOR may do so; granting INSTRUCTOR is OWNER-only.
    @Transactional
    public InviteResponse create(UUID actorId, UUID courseId, CreateInviteRequest request) {
        Membership actor = requireManager(actorId, courseId);

        MembershipRole grantedRole = request.role() != null ? request.role() : MembershipRole.MEMBER;
        if (grantedRole == MembershipRole.OWNER) {
            // A course has exactly one owner (its creator); invites never mint another.
            throw new InsufficientCourseRoleException(courseId);
        }
        if (grantedRole == MembershipRole.INSTRUCTOR && actor.getRole() != MembershipRole.OWNER) {
            throw new InsufficientCourseRoleException(courseId);
        }

        CourseInvite invite = new CourseInvite();
        invite.setCourseSpace(actor.getCourseSpace());
        invite.setCreatedBy(actor.getUser());
        invite.setToken(generateToken());
        invite.setEmail(normalizeEmail(request.email()));
        invite.setRole(grantedRole);
        if (request.expiresInHours() != null) {
            invite.setExpiresAt(Instant.now().plus(Duration.ofHours(request.expiresInHours())));
        }
        inviteRepository.saveAndFlush(invite);

        return InviteResponse.from(invite, courseId);
    }

    @Transactional(readOnly = true)
    public List<InviteResponse> listActive(UUID actorId, UUID courseId) {
        requireManager(actorId, courseId);
        return inviteRepository.findByCourseSpaceIdAndRevokedFalseOrderByCreatedAtDesc(courseId)
                .stream()
                .filter(invite -> !invite.isExpired())
                .map(invite -> InviteResponse.from(invite, courseId))
                .toList();
    }

    @Transactional
    public void revoke(UUID actorId, UUID courseId, UUID inviteId) {
        requireManager(actorId, courseId);
        CourseInvite invite = inviteRepository.findById(inviteId)
                .filter(i -> i.getCourseSpace().getId().equals(courseId))
                .orElseThrow(InviteNotFoundException::new);
        invite.setRevoked(true);
    }

    // Read-only look at where a token leads, so the UI can confirm before joining. Any
    // authenticated user may preview; it never leaks the target email, only whether one binds.
    @Transactional(readOnly = true)
    public InvitePreviewResponse preview(String token) {
        CourseInvite invite = loadRedeemable(token);
        CourseSpace course = invite.getCourseSpace();
        return new InvitePreviewResponse(course.getId(), course.getName(), invite.getRole(),
                invite.getEmail() != null);
    }

    // Redeem a token: the caller becomes a member with the invite's role. Idempotent — an
    // existing member just gets their current membership back, unchanged.
    @Transactional
    public CourseResponse accept(UUID actorId, String token) {
        CourseInvite invite = loadRedeemable(token);
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new UserNotFoundException(actorId));

        if (invite.getEmail() != null && !invite.getEmail().equalsIgnoreCase(actor.getEmail())) {
            throw new InviteEmailMismatchException();
        }

        CourseSpace course = invite.getCourseSpace();
        return membershipRepository.findByCourseIdAndUserId(course.getId(), actorId)
                .map(existing -> CourseResponse.from(existing.getCourseSpace(), existing.getRole()))
                .orElseGet(() -> {
                    Membership membership = new Membership();
                    membership.setCourseSpace(course);
                    membership.setUser(actor);
                    membership.setRole(invite.getRole());
                    membershipRepository.save(membership);

                    // An email invite names one person and is spent on use; an open link stays reusable.
                    if (invite.getEmail() != null) {
                        invite.setRevoked(true);
                    }
                    return CourseResponse.from(course, invite.getRole());
                });
    }

    // Loads a token that is safe to act on, or throws: unknown/revoked → 404, expired → 410.
    private CourseInvite loadRedeemable(String token) {
        CourseInvite invite = inviteRepository.findByTokenWithCourse(token)
                .filter(i -> !i.isRevoked())
                .orElseThrow(InviteNotFoundException::new);
        if (invite.isExpired()) {
            throw new InviteExpiredException();
        }
        return invite;
    }

    // The caller must be a member of the course with a managing role (OWNER or INSTRUCTOR).
    // Distinguishes missing course (404) from present-but-not-yours (403), like CourseService.
    private Membership requireManager(UUID actorId, UUID courseId) {
        Membership membership = membershipRepository.findByCourseIdAndUserId(courseId, actorId)
                .orElseThrow(() -> {
                    if (courseSpaceRepository.existsById(courseId)) {
                        return new NotACourseMemberException(courseId);
                    }
                    return new CourseNotFoundException(courseId);
                });
        if (membership.getRole() == MembershipRole.MEMBER) {
            throw new InsufficientCourseRoleException(courseId);
        }
        return membership;
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }
}
