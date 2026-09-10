package com.studyloop.backend.guide;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.guide.dto.StudyGuideLibraryResponse;
import com.studyloop.backend.guide.dto.StudyGuideResponse;
import com.studyloop.backend.guide.dto.StudyGuideSectionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// The request side of Phase 22: accept a topic, report on the guide, delete one.
//
// **Everything here is scoped to the requester rather than to the course**, which is Phase 21's
// rule applied to a second artifact rather than rediscovered. A guide can be grounded on the
// asking member's own OWNER-visibility notes — that is what makes "write me a guide from the
// lecture and the notes I photographed" work at all — so the finished guide inherits their
// visibility. Sharing one with the class would need a second grounding pass over course-visible
// material only, plus a promotion step with a guard on it, and that is a phase rather than a flag.
@Service
@RequiredArgsConstructor
public class StudyGuideService {

    // Boot 4.1's modular web starter publishes no ObjectMapper bean; this one reads back what
    // StudyGuideStore wrote, so the two are deliberately plain and symmetric.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final TypeReference<List<Citation>> CITATION_LIST = new TypeReference<>() { };

    private final StudyGuideRepository guides;
    private final StudyGuideSectionRepository sections;
    private final CourseAccess courseAccess;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatClient chatClient;

    // Accepts the request and returns immediately; the writing happens on the guide executor.
    //
    // 202 rather than a synchronous guide, and the reason is arithmetic rather than taste: one
    // outline call plus one call per section is six completions, which is tens of seconds. A
    // request that holds a servlet thread that long is a request that a proxy eventually kills
    // halfway through, leaving spending with nothing to show for it. Phase 29.4's watcher already
    // follows long jobs across pages, so there is somewhere for the answer to arrive.
    @Transactional
    public StudyGuideResponse request(UUID actorId, UUID courseId, String topic) {
        Membership membership = courseAccess.requireMember(actorId, courseId);
        requireProvider();

        User requester = userRepository.findById(actorId).orElseThrow();
        StudyGuide guide = new StudyGuide(membership.getCourseSpace(), requester, topic.strip());
        StudyGuide saved = guides.saveAndFlush(guide);

        // An event rather than a direct call, so generation starts after this transaction has
        // committed — see StudyGuideListener for what happens when it does not.
        eventPublisher.publishEvent(new StudyGuideQueuedEvent(saved.getId(), actorId));
        return toResponse(saved, List.of());
    }

    // The member's own guides for this course. Sections are loaded for all of them in one query,
    // so the library is two queries regardless of how many guides or sections it holds.
    @Transactional(readOnly = true)
    public StudyGuideLibraryResponse library(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        List<StudyGuide> rows = guides
                .findByCourseSpaceIdAndRequestedByIdOrderByCreatedAtDesc(courseId, actorId);
        if (rows.isEmpty()) {
            return new StudyGuideLibraryResponse(chatClient.isConfigured(), List.of());
        }
        Map<UUID, List<StudyGuideSection>> byGuide = sections
                .findByGuideIdInOrderByPositionAsc(rows.stream().map(StudyGuide::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(section -> section.getGuide().getId()));

        List<StudyGuideResponse> responses = new ArrayList<>(rows.size());
        for (StudyGuide guide : rows) {
            responses.add(toResponse(guide, byGuide.getOrDefault(guide.getId(), List.of())));
        }
        return new StudyGuideLibraryResponse(chatClient.isConfigured(), responses);
    }

    // One guide with its sections. This is what the page polls while it is being written, which is
    // why the sections are included from the first one onward rather than only when it is READY.
    @Transactional(readOnly = true)
    public StudyGuideResponse get(UUID actorId, UUID courseId, UUID guideId) {
        courseAccess.requireMember(actorId, courseId);
        StudyGuide guide = require(actorId, courseId, guideId);
        return toResponse(guide, sections.findByGuideIdOrderByPositionAsc(guideId));
    }

    // Deleting a guide deletes its sections, by the cascade in V31 — and that cascade is the right
    // one here, unlike the two Phase 27 had to reverse: a section is part of the guide rather than
    // a record of something that happened, and there is nothing left to say once the guide is gone.
    @Transactional
    public void delete(UUID actorId, UUID courseId, UUID guideId) {
        courseAccess.requireMember(actorId, courseId);
        guides.delete(require(actorId, courseId, guideId));
    }

    private StudyGuide require(UUID actorId, UUID courseId, UUID guideId) {
        return guides.findByIdAndCourseSpaceIdAndRequestedById(guideId, courseId, actorId)
                .orElseThrow(() -> new StudyGuideNotFoundException(guideId));
    }

    // Checked at the door rather than in the runner, because it is the one refusal that costs
    // nothing to detect and would otherwise become a FAILED row a minute later saying the same
    // thing. Everything else — including the corpus not covering the topic — is a row, because a
    // student who asked is owed a record of what happened to the ask.
    private void requireProvider() {
        if (!chatClient.isConfigured()) {
            throw new StudyGuideUnavailableException();
        }
    }

    private StudyGuideResponse toResponse(StudyGuide guide, List<StudyGuideSection> rows) {
        List<StudyGuideSectionResponse> sectionResponses = new ArrayList<>(rows.size());
        for (StudyGuideSection row : rows) {
            sectionResponses.add(new StudyGuideSectionResponse(
                    row.getPosition(),
                    row.getHeading(),
                    row.isCovered(),
                    row.getBody(),
                    row.getDiagram(),
                    citations(row)));
        }
        return new StudyGuideResponse(
                guide.getId(),
                guide.getCourseSpace().getId(),
                guide.getTopic(),
                guide.getStatus(),
                guide.getLanguage(),
                guide.getSectionsPlanned(),
                guide.getSectionsWritten(),
                guide.getModelCalls(),
                guide.getError(),
                guide.getCreatedAt(),
                guide.getCompletedAt(),
                sectionResponses);
    }

    // Unreadable JSON yields no citations rather than no guide. The column is written by one
    // method in this codebase and is not user input, so this is defence against a future migration
    // rather than against a caller — and a section whose prose survives with its markers unresolved
    // is a far better outcome than a 500 on a guide that is otherwise intact.
    private List<Citation> citations(StudyGuideSection section) {
        String json = section.getCitations();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, CITATION_LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
