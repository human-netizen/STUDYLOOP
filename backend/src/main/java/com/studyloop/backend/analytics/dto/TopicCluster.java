package com.studyloop.backend.analytics.dto;

import java.time.Instant;
import java.util.List;

// A group of questions that mean roughly the same thing. `label` is a real student question —
// the one closest to the group's centre — not a generated topic name.
public record TopicCluster(
        String label,
        int questionCount,
        // How many of them the corpus could not answer. A large cluster that is entirely
        // ungrounded is the strongest signal this page produces: the class keeps asking something
        // the materials do not cover.
        int ungroundedCount,
        int distinctAskers,
        // Which lectures the grounded members landed on, most-cited first.
        List<TopicLecture> lectures,
        Instant lastAskedAt
) { }
