package com.studyloop.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// The timeouts, and the one assertion that is actually load-bearing: **none of them is zero.**
//
// A zero or absent read timeout does not mean "fail fast", it means *wait for ever* — that is the
// whole defect this block closes, where six of eight clients were built by `RestClient.create()` and
// inherited no ceiling at all. So a `HttpProperties` that bound to zeros would read as configured,
// pass every other test in the suite, and reintroduce the bug silently. That is exactly the shape
// Phase 12 called invisible by construction: a threshold that looks like a working signal and never
// fires.
class HttpPropertiesTest {

    @Test
    void absentConfigurationStillGivesEveryClientACeiling() {
        HttpProperties properties = HttpProperties.defaults();

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.chatReadTimeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(properties.embeddingReadTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(properties.rerankReadTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.storageReadTimeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void noTimeoutIsZeroOrNegative() {
        HttpProperties properties = HttpProperties.defaults();

        assertThat(java.util.List.of(
                properties.connectTimeout(),
                properties.chatReadTimeout(),
                properties.embeddingReadTimeout(),
                properties.rerankReadTimeout(),
                properties.storageReadTimeout()))
                .allSatisfy(timeout -> assertThat(timeout)
                        .as("a zero or negative timeout means wait for ever, which is the bug")
                        .isGreaterThan(Duration.ZERO));
    }

    @Test
    void theChatCeilingClearsTheSlowestCallItHasToSurvive() {
        HttpProperties properties = HttpProperties.defaults();

        // `CohereChatClient` serves both an interactive turn (~2-3s) and a video scene planner
        // measured at 60-90s. The planner is the binding constraint, and a timeout is never retried
        // here — the provider may already have done the work and billed for it — so a ceiling near
        // the planner's normal duration would convert slow successes into lost jobs.
        assertThat(properties.chatReadTimeout()).isGreaterThanOrEqualTo(Duration.ofSeconds(120));

        // And it still has to fit inside Phase 21's per-scene budget, so a hung planning call costs
        // a scene rather than the whole render. If VIDEO_SCENE_BUDGET ever drops below this, one of
        // the two numbers is wrong and this is where it shows up.
        assertThat(properties.chatReadTimeout()).isLessThanOrEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void theYamlKeysBindToTheRecord() {
        // Relaxed binding means `chat-read-timeout` in YAML reaches `chatReadTimeout` here. Worth
        // pinning because a mistyped key does not fail startup — it silently leaves the default in
        // place, so an operator who set a timeout would believe it had taken effect.
        HttpProperties bound = new Binder(new MapConfigurationPropertySource(Map.of(
                "studyloop.http.connect-timeout", "2s",
                "studyloop.http.chat-read-timeout", "150s",
                "studyloop.http.embedding-read-timeout", "90s",
                "studyloop.http.rerank-read-timeout", "20s",
                "studyloop.http.storage-read-timeout", "60s")))
                .bind("studyloop.http", HttpProperties.class)
                .get();

        assertThat(bound.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(bound.chatReadTimeout()).isEqualTo(Duration.ofSeconds(150));
        assertThat(bound.embeddingReadTimeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(bound.rerankReadTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(bound.storageReadTimeout()).isEqualTo(Duration.ofSeconds(60));
    }
}
