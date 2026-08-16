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
        // When the grouping last ran. Null before a course has been clustered at all; the page
        // shows it so an instructor can tell "no topics" from "not computed yet".
        Instant clustersComputedAt
) { }
