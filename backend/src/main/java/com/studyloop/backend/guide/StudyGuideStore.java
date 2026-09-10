package com.studyloop.backend.guide;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.guide.StudyGuidePlanner.WrittenSection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Every write a running guide makes, each in its own short transaction.
//
// **This class exists so that no transaction is open while the model is being called.** It is
// DocumentSummaryStore's arrangement, for DocumentSummaryStore's reason: a completion takes
// seconds, the Supabase pool is five connections, and a runner that wrapped the whole job would
// hold one of them for the length of six completions. Splitting it here also means a guide that
// dies mid-flight has already committed the sections it finished — a partial guide with four of
// six sections is a real artifact a student can read, and rolling it back would be discarding
// paid-for work to preserve a tidiness nobody asked for.
//
// The counters are incremented in the same transaction as the row they describe, which is what
// makes "4 of 6" true at every instant it can be read rather than only at the end.
@Component
@RequiredArgsConstructor
public class StudyGuideStore {

    // Boot 4.1's modular web starter publishes no ObjectMapper bean (see BUGS.md), so — as in
    // ChatHistoryService, which serializes the same record into the same shape of column — we keep
    // our own rather than injecting one that is not there.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StudyGuideRepository guides;
    private final StudyGuideSectionRepository sections;
    private final Clock clock;

    // What the runner needs to do the work, read once and inside a transaction. The course id
    // comes off a LAZY association, and a runner that reached through a detached entity for it
    // would be relying on a proxy's id getter behaving — which it does, until the day the mapping
    // changes and it stops.
    @Transactional(readOnly = true)
    public Optional<GuideJob> read(UUID guideId) {
        return guides.findById(guideId)
                .map(guide -> new GuideJob(guide.getCourseSpace().getId(), guide.getTopic()));
    }

    public record GuideJob(UUID courseId, String topic) { }

    @Transactional
    public void planning(UUID guideId) {
        StudyGuide guide = require(guideId);
        guide.setStatus(StudyGuideStatus.PLANNING);
    }

    // The outline landed: what language it will be written in, how many sections there are, and
    // the one model call it cost. `sectionsPlanned` is only written here, so a guide still in
    // PLANNING reports 0 rather than a number nobody has decided yet.
    @Transactional
    public void planned(UUID guideId, Language language, int sectionCount) {
        StudyGuide guide = require(guideId);
        guide.setLanguage(language);
        guide.setSectionsPlanned(sectionCount);
        guide.setModelCalls(guide.getModelCalls() + 1);
        guide.setStatus(StudyGuideStatus.WRITING);
    }

    // One section, covered or not.
    //
    // `written` is null for a gap, and the two differ in more than a column: a gap has no body, no
    // diagram, no citations and — the part that matters to 22.4 — no model call to count.
    @Transactional
    public void addSection(UUID guideId, int position, String heading, WrittenSection written) {
        StudyGuide guide = require(guideId);
        StudyGuideSection section = new StudyGuideSection(guide, position, heading);
        if (written != null) {
            section.setCovered(true);
            section.setBody(written.body());
            section.setDiagram(written.diagram());
            section.setCitations(toJson(written.citations()));
            guide.setSectionsWritten(guide.getSectionsWritten() + 1);
            guide.setModelCalls(guide.getModelCalls() + 1);
        }
        sections.save(section);
    }

    @Transactional
    public void complete(UUID guideId) {
        finish(guideId, StudyGuideStatus.READY, null);
    }

    // The corpus cannot support the topic. Terminal, and not an error: nothing was spent past one
    // embedding, and there is no `error` text because nothing went wrong.
    @Transactional
    public void refuse(UUID guideId) {
        finish(guideId, StudyGuideStatus.REFUSED, null);
    }

    @Transactional
    public void fail(UUID guideId, String reason) {
        finish(guideId, StudyGuideStatus.FAILED, reason);
    }

    private void finish(UUID guideId, StudyGuideStatus status, String error) {
        StudyGuide guide = require(guideId);
        guide.setStatus(status);
        guide.setError(error);
        guide.setCompletedAt(clock.instant());
    }

    private StudyGuide require(UUID guideId) {
        return guides.findById(guideId).orElseThrow(() -> new StudyGuideNotFoundException(guideId));
    }

    // Citations are stored as the JSON the client is given, so what a reader sees a week later is
    // what was on the page the day it was written (V29's argument, one table over).
    private String toJson(List<Citation> citations) {
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (JsonProcessingException e) {
            // A Citation is a record of strings, UUIDs and ints; this cannot fail without the
            // mapper itself being misconfigured, and a guide is not the place to discover that.
            throw new StudyGuideException("Could not serialize this section's citations.", e);
        }
    }
}
