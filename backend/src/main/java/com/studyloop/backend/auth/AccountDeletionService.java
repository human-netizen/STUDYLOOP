package com.studyloop.backend.auth;

import com.studyloop.backend.course.LastOwnerException;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRepository;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.document.DocumentLifecycleService.DocumentBytesOrphanedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// Deleting an account (Phase 27.4).
//
// **The row survives as a tombstone; everything personal is destroyed.** That is a deviation from
// what the plan sketched — `question_events.asked_by` to `set null` — and the reason is that
// `asked_by` is one of *ten* foreign keys into `users` with no `on delete` clause. Making every
// one of them nullable would be ten migrations of column surgery and ten Java associations that
// stop being `optional = false`, to reach a state where a course's own history has holes in it:
// a quiz with no author, a forum thread nobody wrote, a document nobody uploaded.
//
// Scrubbing the row reaches the same end more honestly. What a person is entitled to have removed
// is their identity and their private work, and both go:
//
//   * the email, name and password hash are overwritten, so the account cannot be logged into,
//     matched by an invite, or recognised in any list;
//   * every membership is deleted, so they are out of every course;
//   * their private notes are deleted with their bytes, their conversations, their flashcards
//     (and the review states that cascade from them) and their quiz attempts.
//
// What stays is what belongs to the courses rather than to the person: uploaded material, forum
// threads and answers, generated quizzes, and the anonymised question counts — which never
// exposed identity in the first place, and which the plan already argued should outlive the
// account. `distinct_askers` also stays exact, which the `set null` design would have given up.
//
// **It refuses while the caller is the last OWNER of any course.** Deleting an account is leaving
// every course at once, and the last-owner rule is not weaker because it is being applied in
// bulk: a course with no owner is unadministrable and no cascade would notice.
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void delete(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        requireNotLastOwnerAnywhere(userId);

        // Read the storage paths before the rows go. After the delete there is nothing left to
        // ask where the bytes were.
        List<String> notePaths = jdbc.queryForList("""
                select storage_path from documents
                where uploaded_by = ? and visibility = 'OWNER' and storage_path is not null
                """, String.class, userId);

        // Their private notes. Course material they uploaded is deliberately not here: it is the
        // course's corpus, a manager accepted it, and other people's answers cite it.
        int notes = jdbc.update(
                "delete from documents where uploaded_by = ? and visibility = 'OWNER'", userId);
        // Chat is private by construction — a conversation has one member.
        int conversations = jdbc.update(
                "delete from chat_conversations where created_by = ?", userId);
        // review_states cascades from flashcards, so this is one statement for both.
        int flashcards = jdbc.update("delete from flashcards where created_by = ?", userId);
        // quiz_attempt_answers cascades from quiz_attempts.
        int attempts = jdbc.update("delete from quiz_attempts where user_id = ?", userId);
        int memberships = jdbc.update("delete from memberships where user_id = ?", userId);

        // The tombstone. A per-account unique value rather than a shared one, because `email` is
        // unique and two deleted accounts must both be able to hold it — and `@deleted.invalid` is
        // a reserved TLD (RFC 2606), so no real address can ever collide with one.
        user.setEmail("deleted-" + userId + "@deleted.invalid");
        user.setDisplayName("Deleted account");
        // Not a hash of anything. BCrypt will never verify a password against a string that is not
        // a valid hash, so every login attempt against this row fails at the format check.
        user.setPasswordHash("account-deleted");
        userRepository.saveAndFlush(user);

        notePaths.forEach(path -> eventPublisher.publishEvent(new DocumentBytesOrphanedEvent(path)));

        log.info("Deleted account {} - {} note(s), {} conversation(s), {} flashcard(s), "
                        + "{} quiz attempt(s), {} membership(s)",
                userId, notes, conversations, flashcards, attempts, memberships);
    }

    // Named rather than counted: "you own three courses" is a refusal, and "you own Algorithms,
    // Databases and Networks" is a to-do list.
    private void requireNotLastOwnerAnywhere(UUID userId) {
        List<String> orphaned = membershipRepository.findByUserId(userId).stream()
                .filter(membership -> membership.getRole() == MembershipRole.OWNER)
                .filter(membership -> membershipRepository.countByCourseSpaceIdAndRole(
                        membership.getCourseSpace().getId(), MembershipRole.OWNER) <= 1)
                .map(Membership::getCourseSpace)
                .map(course -> "\"" + course.getName() + "\"")
                .toList();
        if (!orphaned.isEmpty()) {
            throw new LastOwnerException(String.join(", ", orphaned));
        }
    }
}
