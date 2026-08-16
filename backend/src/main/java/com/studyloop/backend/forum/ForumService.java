package com.studyloop.backend.forum;

import com.studyloop.backend.analytics.QuestionLogService;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.forum.ForumAnswerRepository.ThreadAnswerCount;
import com.studyloop.backend.forum.dto.CreateThreadRequest;
import com.studyloop.backend.forum.dto.ForumAnswerResponse;
import com.studyloop.backend.forum.dto.ForumThreadDetail;
import com.studyloop.backend.forum.dto.ForumThreadSummary;
import com.studyloop.backend.forum.dto.PostAnswerRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// The escalation loop (Phase 9.2). A question the assistant refused becomes a thread, the class
// answers it, and a manager accepting an answer writes it back into the corpus.
//
// The permissions are not uniform across those three steps, and the asymmetry is the point:
//
//   open a thread   any member — refusals come from students, so escalation must too
//   post an answer  any member — a classmate who knows the answer is the fastest route to it
//   accept          managers only
//
// Accepting is manager-only because it is not a "mark as solved" button: it puts member-written
// text into the corpus that every future answer in this course may be grounded on and cite. The
// same guard governs uploading a PDF (a course's corpus is curated, not crowd-sourced), and it
// would be strange for the route through the forum to be the open one. It is also the injection
// boundary — uploaded documents are already untrusted input to the model, and this is the one
// path where any member can write into that input, so a human decides what goes in.
@Service
@RequiredArgsConstructor
public class ForumService {

    private static final int MAX_TITLE_LENGTH = 300;

    private final CourseAccess courseAccess;
    private final ForumThreadRepository threadRepository;
    private final ForumAnswerRepository answerRepository;
    private final ForumCorpusService corpusService;
    private final QuestionLogService questionLog;

    @Transactional(readOnly = true)
    public List<ForumThreadSummary> list(UUID actorId, UUID courseId, ForumThreadStatus status) {
        courseAccess.requireMember(actorId, courseId);

        List<ForumThread> threads = status == null
                ? threadRepository.findByCourseWithAuthors(courseId)
                : threadRepository.findByCourseAndStatusWithAuthors(courseId, status);
        Map<UUID, Integer> answerCounts = answerCounts(threads);

        List<ForumThreadSummary> summaries = new ArrayList<>(threads.size());
        for (ForumThread thread : threads) {
            summaries.add(ForumThreadSummary.from(thread, answerCounts.getOrDefault(thread.getId(), 0)));
        }
        return summaries;
    }

    @Transactional(readOnly = true)
    public ForumThreadDetail get(UUID actorId, UUID courseId, UUID threadId) {
        Membership member = courseAccess.requireMember(actorId, courseId);
        return detail(requireThread(courseId, threadId), member);
    }

    @Transactional
    public ForumThreadDetail open(UUID actorId, UUID courseId, CreateThreadRequest request) {
        Membership member = courseAccess.requireMember(actorId, courseId);

        // A question id that isn't this course's is dropped rather than rejected: the thread is
        // still a perfectly good question, and the only thing lost is the back-link. Rejecting it
        // would turn a client bug into a student unable to ask anything.
        UUID questionEventId = questionLog.isCourseQuestion(courseId, request.questionEventId())
                ? request.questionEventId()
                : null;

        if (questionEventId != null) {
            Optional<ForumThread> existing =
                    threadRepository.findByCourseSpaceIdAndQuestionEventId(courseId, questionEventId);
            if (existing.isPresent()) {
                // Two people escalating the same refusal means one discussion, not two halves of
                // one. Returning the existing thread also makes the client's "escalate" button
                // idempotent, so a double click lands somewhere sensible.
                return detail(existing.get(), member);
            }
        }

        ForumThread thread = new ForumThread();
        thread.setCourseSpace(member.getCourseSpace());
        thread.setCreatedBy(member.getUser());
        thread.setTitle(trimTitle(request.title()));
        thread.setBody(blankToNull(request.body()));
        thread.setQuestionEventId(questionEventId);
        thread.setStatus(ForumThreadStatus.OPEN);
        // Flush so the generated id and timestamps are populated before we map the response.
        threadRepository.saveAndFlush(thread);

        return detail(thread, member);
    }

    @Transactional
    public ForumThreadDetail answer(UUID actorId, UUID courseId, UUID threadId, PostAnswerRequest request) {
        Membership member = courseAccess.requireMember(actorId, courseId);
        ForumThread thread = requireThread(courseId, threadId);

        ForumAnswer answer = new ForumAnswer();
        answer.setThread(thread);
        answer.setCreatedBy(member.getUser());
        answer.setBody(request.body().strip());
        answerRepository.saveAndFlush(answer);

        return detail(thread, member);
    }

    // Accepting is the corpus write. See the class comment for why it is manager-only.
    @Transactional
    public ForumThreadDetail accept(UUID actorId, UUID courseId, UUID threadId, UUID answerId) {
        Membership member = courseAccess.requireManager(actorId, courseId);
        ForumThread thread = requireThread(courseId, threadId);
        ForumAnswer answer = answerRepository.findByIdAndThreadId(answerId, threadId)
                .orElseThrow(() -> new ForumAnswerNotFoundException(answerId));

        thread.setAcceptedAnswerId(answer.getId());
        thread.setStatus(ForumThreadStatus.ANSWERED);
        // Best effort, and re-runnable: accepting again (the same reply or a better one) replaces
        // this thread's document rather than adding another.
        thread.setAnswerDocumentId(corpusService.publish(thread, answer));

        return detail(thread, member);
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private ForumThread requireThread(UUID courseId, UUID threadId) {
        return threadRepository.findByIdAndCourseSpaceId(threadId, courseId)
                .orElseThrow(() -> new ForumThreadNotFoundException(threadId));
    }

    private ForumThreadDetail detail(ForumThread thread, Membership member) {
        List<ForumAnswerResponse> answers =
                answerRepository.findByThreadWithAuthors(thread.getId()).stream()
                        .map(answer -> ForumAnswerResponse.from(answer, thread))
                        .toList();
        return ForumThreadDetail.from(thread, answers, member.getRole() != MembershipRole.MEMBER);
    }

    private Map<UUID, Integer> answerCounts(List<ForumThread> threads) {
        if (threads.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = threads.stream().map(ForumThread::getId).toList();
        Map<UUID, Integer> counts = new HashMap<>();
        for (ThreadAnswerCount row : answerRepository.countByThreadIds(ids)) {
            counts.put(row.getThreadId(), (int) row.getTotal());
        }
        return counts;
    }

    private static String trimTitle(String title) {
        String oneLine = title.replaceAll("\\s+", " ").strip();
        return oneLine.length() > MAX_TITLE_LENGTH ? oneLine.substring(0, MAX_TITLE_LENGTH) : oneLine;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
