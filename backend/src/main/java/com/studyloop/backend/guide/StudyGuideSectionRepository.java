package com.studyloop.backend.guide;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StudyGuideSectionRepository extends JpaRepository<StudyGuideSection, UUID> {

    // Reading order, always. `position` and never createdAt — see V31 for why a row whose order is
    // implied by when it was inserted is a row that reorders itself the day two are written at once.
    List<StudyGuideSection> findByGuideIdOrderByPositionAsc(UUID guideId);

    List<StudyGuideSection> findByGuideIdInOrderByPositionAsc(List<UUID> guideIds);
}
