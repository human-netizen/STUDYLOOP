package com.studyloop.backend.guide;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudyGuideRepository extends JpaRepository<StudyGuide, UUID> {

    List<StudyGuide> findByCourseSpaceIdAndRequestedByIdOrderByCreatedAtDesc(UUID courseId, UUID requestedBy);

    Optional<StudyGuide> findByIdAndCourseSpaceIdAndRequestedById(UUID id, UUID courseId, UUID requestedBy);

    List<StudyGuide> findByStatusIn(List<StudyGuideStatus> statuses);

    // The startup sweep's one write (see StudyGuideReconciler).
    //
    // **Native, and that is not a preference.** A guide left mid-flight is reached by a process
    // that has just started and holds no persistence context for these rows; the JPA route would
    // load every stranded guide to change one column each. The cost of native SQL here is the cost
    // Phase 20 found in Phase 4's code — a status write nothing else can see — so nothing else
    // reads a guide's status inside this transaction, and the sweep runs before any request can.
    @Modifying
    @Query(value = """
            update study_guides
               set status = 'FAILED',
                   error = :reason,
                   completed_at = :now
             where status not in ('READY', 'FAILED', 'REFUSED')
            """, nativeQuery = true)
    int failUnfinished(@Param("reason") String reason, @Param("now") Instant now);
}
