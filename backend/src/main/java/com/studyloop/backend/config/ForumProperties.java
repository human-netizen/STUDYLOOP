package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Settings for the corpus watch (Phase 20.1): the sweep that answers a course's open forum
// threads when a new document arrives.
@ConfigurationProperties(prefix = "studyloop.forum")
public record ForumProperties(
        // Master switch for the sweep. Off means an upload behaves exactly as it did in Phase 19 —
        // the comparison to run when demonstrating what the watch is worth, and the setting to
        // reach for if a course's threads start filling with answers nobody wanted.
        boolean watchEnabled,
        // How many of a course's open threads one upload may re-check, newest first.
        //
        // The bound exists because the cost of this feature is not paid per student, it is paid
        // per *upload* — a course with sixty stale open threads would otherwise turn one PDF into
        // sixty retrievals and up to sixty model calls, on an executor nobody is watching. Newest
        // first, because a thread opened this week is one somebody is still waiting on.
        int watchMaxThreads
) { }
