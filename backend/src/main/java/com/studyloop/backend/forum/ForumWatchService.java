package com.studyloop.backend.forum;

import com.studyloop.backend.chat.CorpusAnswerService;
import com.studyloop.backend.chat.CorpusAnswerService.GroundedAnswer;
import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.config.ForumProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The corpus watch (Phase 20.1). When a document finishes ingesting, every open thread in that
// course is asked again — and any the corpus can now answer gets an answer.
//
// **This is the inversion of the community bot it was modelled on, and the inversion is the whole
// design.** That one answers when the person who was mentioned is offline, which puts a model in
// the position of speaking for someone who has not replied yet. StudyLoop's threads are created by
// refusals: the question already went to the assistant, and the assistant already said it did not
// know. So the only honest moment for the machine to speak again is the moment that stops being
// true — when material arrives that answers it.
//
// Everything else follows from that:
//
//   the trigger    a real event (a document reached READY), not a heartbeat and not a poll. There
//                  is no presence table to keep, no "offline after N minutes" to tune, and no way
//                  for the bot to answer a thread that nothing new has been learned about.
//   the gate       the same confidence gate as chat. A thread that is still unanswerable gets
//                  silence, which is the same answer the student already had, and costs nothing.
//   the status     the thread stays OPEN. A person's answer still supersedes this one, and only a
//                  manager accepting a *person's* answer writes to the corpus. 9.2 is unchanged.
//   the ceiling    one machine answer per thread, ever, and at most watchMaxThreads threads per
//                  upload.
//
// It also produces the best available evidence that the confidence gate is calibrated rather than
// arbitrary: the same question, refused before an upload and answered after it, with the cosine
// recorded both times and nothing else about the pipeline changed.
@Service
@RequiredArgsConstructor
public class ForumWatchService {

    private static final Logger log = LoggerFactory.getLogger(ForumWatchService.class);

    private final ForumProperties properties;
    private final ForumThreadRepository threadRepository;
    private final ForumAnswerRepository answerRepository;
    private final CorpusAnswerService corpusAnswerService;

    // Called at the end of ingestion, on the ingestion executor. Never throws: a course's forum is
    // downstream of a document being usable, and a thread that could not be answered must not turn
    // a perfectly good upload into a FAILED one. The caller has already marked the document READY.
    public void sweep(UUID courseId, UUID documentId) {
        if (!properties.watchEnabled()) {
            return;
        }
        try (var ignored = AiUsageContext.of(AiOperation.FORUM_ANSWER)) {
            int answered = 0;
            for (ForumThread thread : openThreads(courseId)) {
                if (answerThread(courseId, thread, documentId)) {
                    answered++;
                }
            }
            if (answered > 0) {
                log.info("Document {} answered {} open thread(s) in course {}",
                        documentId, answered, courseId);
            }
        } catch (RuntimeException e) {
            log.warn("Corpus watch failed for course {} after document {}: {}",
                    courseId, documentId, e.getMessage());
        }
    }

    private List<ForumThread> openThreads(UUID courseId) {
        return threadRepository.findOpenByCourse(courseId, Limit.of(properties.watchMaxThreads()));
    }

    // One thread, and deliberately *not* inside a transaction of its own.
    //
    // Two reasons, and they point the same way. Each step here is a single statement — an exists
    // check, then one insert — so there is no invariant spanning two writes for a transaction to
    // protect; eight open threads are eight independent attempts, and a provider timing out on the
    // fifth should leave the four answers before it exactly where they are. And the slow part is a
    // model call: wrapping this would hold a pooled Supabase connection open across it, which is
    // the specific thing the streaming chat path was split in half to avoid.
    //
    // `courseId` is passed in rather than read off `thread.getCourseSpace()`. The threads were
    // loaded by a repository call that has since committed, so they are detached and that
    // association is an uninitialized proxy — touching it here would throw, on the ingestion
    // executor, where no request is watching. Their basic fields are already loaded and safe.
    private boolean answerThread(UUID courseId, ForumThread thread, UUID documentId) {
        // Checked before retrieval, not after: the guard is what stops a course importing a
        // semester of lectures from paying for the same thread eleven times.
        if (answerRepository.existsByThreadIdAndAuthorKind(thread.getId(), ForumAnswerAuthor.ASSISTANT)) {
            return false;
        }

        // The title, not the title plus the body. The title *is* the question chat refused —
        // escalation sends the exact wording back — while the body is what the student added
        // afterwards, which is usually "I could not find this in the slides" and is noise to a
        // retriever that will happily match "slides".
        Optional<GroundedAnswer> answer;
        try {
            answer = corpusAnswerService.answer(courseId, thread.getTitle());
        } catch (RuntimeException e) {
            // One thread's failure, not the sweep's. A provider outage during a bulk import would
            // otherwise take out every thread after the first.
            log.warn("Could not answer thread {}: {}", thread.getId(), e.getMessage());
            return false;
        }
        if (answer.isEmpty()) {
            // The gate still refuses. Silence is correct: the student has already been told this
            // once, and a second "still not in the materials" is a notification about nothing.
            return false;
        }

        ForumAnswer posted = new ForumAnswer();
        posted.setThread(thread);
        posted.setAuthorKind(ForumAnswerAuthor.ASSISTANT);
        posted.setBody(withSources(answer.get()));
        posted.setSourceDocumentId(documentId);
        posted.setTopSimilarity(answer.get().topSimilarity());
        answerRepository.saveAndFlush(posted);
        return true;
    }

    // The answer carries [n] markers, so the reply has to carry what they point at. A forum post is
    // one text column with no citation model behind it, and inventing one for a single writer would
    // be a schema for a feature — so the sources are appended as text, in the same order and with
    // the same numbers the model used.
    //
    // Without this the markers would be the worst of both worlds: they look like the citations the
    // rest of the product has trained the reader to click, and they resolve to nothing.
    private static String withSources(GroundedAnswer answer) {
        StringBuilder body = new StringBuilder(answer.text().strip());
        if (answer.citations().isEmpty()) {
            return body.toString();
        }
        body.append("\n\nSources:\n");
        for (Citation citation : answer.citations()) {
            body.append("- [").append(citation.index()).append("] ").append(citation.filename());
            if (citation.pageNumber() != null) {
                body.append(", p.").append(citation.pageNumber());
            }
            body.append('\n');
        }
        return body.toString();
    }
}
