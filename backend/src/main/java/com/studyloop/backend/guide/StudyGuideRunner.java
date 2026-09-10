package com.studyloop.backend.guide;

import com.studyloop.backend.guide.StudyGuidePlanner.Outline;
import com.studyloop.backend.guide.StudyGuidePlanner.WrittenSection;
import com.studyloop.backend.guide.StudyGuideStore.GuideJob;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Phase 22 — the guide, made. Retrieval and the model calls belong to StudyGuidePlanner and every
// write belongs to StudyGuideStore; what is here is the order they happen in and what each failure
// means.
//
// **Nothing in this class is transactional, and that is the design rather than an omission.** A
// guide is a sequence of provider calls with a short write after each one. Wrapping the sequence
// would hold one of five pooled Supabase connections for the length of every completion in it —
// Phase 26.2's finding, in the shape that would show it worst — and it would also throw away a
// partially written guide on the last section's failure. A guide that got four sections in before
// the provider fell over is four sections a student can read, and it says FAILED on the front.
@Service
@RequiredArgsConstructor
public class StudyGuideRunner {

    private static final Logger log = LoggerFactory.getLogger(StudyGuideRunner.class);

    private final StudyGuidePlanner planner;
    private final StudyGuideStore store;

    public void run(UUID guideId, UUID actorId) {
        GuideJob job = store.read(guideId).orElse(null);
        if (job == null) {
            // Requested and then deleted while it waited for a slot. Nothing to write to.
            log.warn("Study guide {} disappeared before it was generated", guideId);
            return;
        }
        UUID courseId = job.courseId();
        String topic = job.topic();

        try {
            store.planning(guideId);
            Optional<Outline> outline = planner.outline(actorId, courseId, topic);
            if (outline.isEmpty()) {
                // The gate's decision. One embedding spent, no completion, and the student is
                // told the true reason rather than shown an error.
                log.info("Study guide {} refused: the corpus does not cover \"{}\"", guideId, topic);
                store.refuse(guideId);
                return;
            }

            List<String> headings = outline.get().headings();
            store.planned(guideId, outline.get().language(), headings.size());

            int gaps = 0;
            for (int i = 0; i < headings.size(); i++) {
                String heading = headings.get(i);
                // **A section's failure is not the guide's failure.** The provider dropping one
                // completion out of six is the ordinary weather of this system, and the honest
                // outcome is the section it cost rather than the guide. It is recorded as a gap,
                // which is the one state the reader already understands as "nothing here".
                WrittenSection written;
                try {
                    written = planner.write(actorId, courseId, heading, outline.get().language())
                            .orElse(null);
                } catch (StudyGuideException e) {
                    log.warn("Study guide {} lost section \"{}\": {}", guideId, heading, e.getMessage());
                    written = null;
                }
                if (written == null) {
                    gaps++;
                }
                store.addSection(guideId, i + 1, heading, written);
            }

            store.complete(guideId);
            log.info("Study guide {} ready: {} sections, {} of them gaps", guideId, headings.size(), gaps);
        } catch (RuntimeException e) {
            // Everything else: the outline call failed, the provider is down, the topic vanished
            // with its course. The row is the only place this can be reported — the request
            // returned 202 minutes ago.
            log.error("Study guide {} failed", guideId, e);
            store.fail(guideId, e.getMessage());
        }
    }
}
