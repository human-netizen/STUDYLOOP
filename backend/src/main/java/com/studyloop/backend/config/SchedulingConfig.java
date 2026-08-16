package com.studyloop.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Turns on @Scheduled. Separate from AsyncConfig because the two solve different problems:
// @Async moves a caller's work off its own thread, while @Scheduled runs work nobody asked for.
//
// One job uses it today — QuestionClusterScheduler, warming Phase 9.1's topic clusters. Spring's
// default scheduler is a single thread, which is correct here: the job is a periodic sweep, and
// running two of them at once would only race to write the same table.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
