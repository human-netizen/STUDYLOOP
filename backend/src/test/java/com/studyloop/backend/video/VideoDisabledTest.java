package com.studyloop.backend.video;

import com.studyloop.backend.config.VideoProperties;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.video.dto.VideoLibraryResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// What an installation without a renderer does, which is nothing at all — and no Spring context to
// prove it with.
//
// This is a plain unit test on purpose. The behaviour under test is entirely a property of one
// boolean, and asserting it in a @SpringBootTest would mean a second application context with a
// second Hikari pool against a database whose session pooler caps clients at fifteen. The other
// video tests already pay for one context; this one costs nothing.
//
// **The rule being pinned is "absent, not broken".** With the flag off the page draws no button
// (the library says so) and the endpoint answers 503 with a reason rather than accepting a request
// it cannot serve. Phase 15.1 settled this for a missing vision key; the failure it avoids is the
// one where a demo machine shows a feature that dies on click.
class VideoDisabledTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();

    @Test
    void requestingAVideoIsRefusedWithAReasonRatherThanAccepted() {
        VideoService service = serviceWith(false);

        VideoDisabledException thrown = assertThrows(
                VideoDisabledException.class,
                () -> service.request(ACTOR, COURSE, "Anything at all"));

        assertTrue(thrown.getMessage().contains("not switched on"), thrown.getMessage());
    }

    // The flag reaches the client in the payload the page already fetches, so the button and the
    // job list cannot be drawn from two different answers to the same question.
    @Test
    void theLibrarySaysTheFeatureDoesNotExistHere() {
        VideoLibraryResponse library = serviceWith(false).library(ACTOR, COURSE);

        assertFalse(library.enabled());
        assertFalse(library.workerReachable());
        assertTrue(library.jobs().isEmpty());
        assertEquals(0, library.usedToday());
    }

    // Nothing downstream is touched: no repository, no storage, no health probe against a service
    // that is not there. The nulls below are the assertion — if any of them were dereferenced this
    // test would fail with a NullPointerException, which is exactly the check being made.
    private static VideoService serviceWith(boolean enabled) {
        return new VideoService(null, null, null, null, null, null, properties(enabled),
                allowAnyMember(), null, null);
    }

    private static VideoProperties properties(boolean enabled) {
        return new VideoProperties(enabled, "", "./data/videos", 1, 3, 6, 4, 150,
                Duration.ofMinutes(3), 2, Duration.ofMinutes(25), 1280, 720, 30, 6,
                new VideoProperties.Voices("en-US-AriaNeural", "bn-BD-NabanitaNeural"));
    }

    // A membership check that always passes, so the test is about the flag rather than about
    // authorization — which has its own tests, against a real database, in VideoGenerationTest.
    private static CourseAccess allowAnyMember() {
        return new CourseAccess(null, null) {
            @Override
            public Membership requireMember(UUID actorId, UUID courseId) {
                return new Membership();
            }
        };
    }
}
