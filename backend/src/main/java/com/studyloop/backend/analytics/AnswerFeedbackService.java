package com.studyloop.backend.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.analytics.AnswerFeedbackRepository.ComplaintRow;
import com.studyloop.backend.analytics.dto.AnswerComplaint;
import com.studyloop.backend.chat.dto.AnswerFeedbackRequest;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.course.CourseAccess;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Phase 28.3 — recording what a reader thought of an answer, and reading it back for the
// instructor.
//
// **Zero provider calls and zero query-time cost.** Nothing on the chat path reads this table; the
// write happens on a click that the reader chose to make, and the read happens on a page an
// instructor opens. A feature that costs nothing per question is a feature that can stay on.
@Service
@RequiredArgsConstructor
public class AnswerFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(AnswerFeedbackService.class);

    // How many reported answers the instructor's page carries. A list, not a report: past twenty
    // the useful thing is the count, which the tally beside it already gives.
    private static final int MAX_COMPLAINTS = 20;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CourseAccess courseAccess;
    private final AnswerFeedbackRepository repository;
    private final QuestionLogService questionLog;

    // Any course member may report an answer they were given.
    //
    // **The event id is checked against the course and then trusted for nothing else.**
    // `isCourseQuestion` is the check the forum already makes before attaching a thread to a
    // refusal (9.2), and it is the only thing standing between a client-supplied id and a row
    // pointing into another course's analytics. An id that fails it is dropped rather than
    // rejected: the verdict still has its question and its citations, which is the actionable part,
    // and a 400 would lose real feedback over a stale handle.
    @Transactional
    public void submit(UUID actorId, UUID courseId, AnswerFeedbackRequest request) {
        courseAccess.requireMember(actorId, courseId);

        UUID eventId = request.answerEventId();
        if (eventId != null && !questionLog.isCourseQuestion(courseId, eventId)) {
            log.warn("Feedback referenced a question outside course {}; storing it unlinked", courseId);
            eventId = null;
        }

        repository.upsert(UUID.randomUUID(), courseId, eventId, actorId,
                Boolean.TRUE.equals(request.helpful()), blankToNull(request.reason()),
                request.question(), citationsJson(request.citations()));
    }

    // The reported answers and the tally, for one course over the confusion page's window.
    @Transactional(readOnly = true)
    public Reported reported(UUID courseId, Instant since) {
        AnswerFeedbackRepository.Tally tally = repository.tally(courseId, since);
        List<ComplaintRow> rows = repository.complaints(courseId, since, MAX_COMPLAINTS);
        List<AnswerComplaint> complaints = new ArrayList<>(rows.size());
        for (ComplaintRow row : rows) {
            complaints.add(new AnswerComplaint(row.questionEventId(), row.question(), row.reason(),
                    row.reportedAt(), readCitations(row.citationsJson())));
        }
        return new Reported(tally.helpful(), tally.unhelpful(), complaints);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String citationsJson(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (Exception e) {
            // The verdict is the thing that must land. Losing the passage list makes the row less
            // useful and still leaves it more useful than nothing.
            log.warn("Feedback citations could not be stored: {}", e.getMessage());
            return "[]";
        }
    }

    private List<Citation> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Citation>>() { });
        } catch (Exception e) {
            log.warn("Stored feedback citations were unreadable: {}", e.getMessage());
            return List.of();
        }
    }

    // Both halves of what the instructor's page shows: the counts that give the list a
    // denominator, and the list itself.
    public record Reported(int helpful, int unhelpful, List<AnswerComplaint> complaints) { }
}
