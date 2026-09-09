package com.studyloop.backend.analytics.dto;

import java.time.Instant;
import java.util.List;

// Everything the instructor's confusion page renders, in one response. Deliberately one call
// rather than four: the four blocks are read together, share a window, and are meaningless
// individually — a topic list without the totals cannot say whether "8 questions" is most of the
// class or a rounding error.
public record ConfusionReport(
        int windowDays,
        ConfusionTotals totals,
        // Per-lecture heat, hottest first. Includes documents with zero questions.
        List<LectureHeat> lectures,
        // Questions grouped by meaning, biggest group first.
        List<TopicCluster> topics,
        // Individual questions the corpus could not answer, newest first.
        List<UngroundedQuestion> ungrounded,
        // Phase 28.3 — answers a reader reported as wrong, newest first, with the passages each
        // was shown with. Beside the list above and not inside it: a refusal about uncovered
        // material and a wrong answer about covered material are opposite problems, and this page
        // could only show the first until this phase.
        List<AnswerComplaint> reportedAnswers,
        // How many verdicts of each kind landed in the window. The list above has no meaning
        // without them — three reports out of four answers and three out of four hundred are not
        // the same course.
        int helpfulVotes,
        int unhelpfulVotes,
        // When the grouping last ran. Null before a course has been clustered at all; the page
        // shows it so an instructor can tell "no topics" from "not computed yet".
        Instant clustersComputedAt
) { }
