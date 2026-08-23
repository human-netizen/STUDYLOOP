package com.studyloop.backend.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 17 — the visual pipeline's configuration, and the values that would switch it off silently.
//
// Same reasoning as VisionPropertiesTest: absent configuration has to land on the pipeline that
// was measured rather than on a zero the first upload divides by, and a share of a page has values
// that are not merely wrong but unreachable. An unreachable threshold reads as a live signal that
// never fires, which is the failure mode Phase 12 named as invisible by construction.
class VisualPropertiesTest {

    @Test
    void absentConfigurationIsTheShippedPipeline() {
        VisualProperties properties = VisualProperties.defaults();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.dpi()).isEqualTo(120);
        assertThat(properties.maxPagesPerDocument()).isEqualTo(60);
        assertThat(properties.minImageCoverage()).isEqualTo(0.15);
        assertThat(properties.minVectorSegments()).isEqualTo(150);
    }

    @Test
    void aCoverageThatCanNeverBeReachedIsAMisconfigurationRatherThanAThreshold() {
        // A page cannot be more than fully covered — PageGraphics caps the measurement at 1 for
        // exactly that reason — so 1.0 here is not a strict setting. It is the raster half of the
        // signal switched off by a typo, and nothing downstream would ever say so.
        assertThat(new VisualProperties(true, 0, 0, 1.0, 0).minImageCoverage()).isEqualTo(0.15);
        assertThat(new VisualProperties(true, 0, 0, -0.2, 0).minImageCoverage()).isEqualTo(0.15);
    }

    @Test
    void offIsAnHonestSettingAndZeroIsNot() {
        // The distinction the record has to keep: `enabled=false` is a deployment choosing the
        // Phase 16 corpus, and it survives. A zero DPI or a zero page cap is nobody's choice — it
        // is an unset property, and it falls back rather than rendering nothing at no resolution.
        VisualProperties off = new VisualProperties(false, 0, 0, 0, 0);

        assertThat(off.enabled()).isFalse();
        assertThat(off.dpi()).isEqualTo(120);
        assertThat(off.maxPagesPerDocument()).isEqualTo(60);
    }

    @Test
    void explicitValuesAreKept() {
        VisualProperties properties = new VisualProperties(true, 200, 8, 0.4, 400);

        assertThat(properties.dpi()).isEqualTo(200);
        assertThat(properties.maxPagesPerDocument()).isEqualTo(8);
        assertThat(properties.minImageCoverage()).isEqualTo(0.4);
        assertThat(properties.minVectorSegments()).isEqualTo(400);
    }
}
