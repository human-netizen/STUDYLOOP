package com.studyloop.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// "Today" is a scheduling input for spaced repetition, so it arrives as an injected Clock rather
// than a LocalDate.now() call buried in a service. Production gets the system clock; a test can
// replace this bean with Clock.fixed(...) and watch a card come due days later without waiting
// days — see ReviewScheduleTest.
@Configuration
public class ClockConfig {

    // UTC, not the server's zone: due dates must not shift when the app moves regions.
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
