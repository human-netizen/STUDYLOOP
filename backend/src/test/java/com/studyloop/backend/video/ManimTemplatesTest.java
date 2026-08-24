package com.studyloop.backend.video;

import com.studyloop.backend.video.ManimTemplates.Pacing;
import com.studyloop.backend.video.VideoPlanner.AnimationSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// The drawn-in-advance animations, checked without rendering one.
//
// What is worth asserting here is not that Manim likes the file — that is the worker's suite and a
// two-minute render — but that the *data* put into it is right: a binary search that visits the
// right cells, a sort trace that actually sorts, and a run time that adds up to the narration it
// was given. Every one of those is a defect that renders perfectly and is wrong on screen.
class ManimTemplatesTest {

    private final ManimTemplates templates = new ManimTemplates();

    private static final Pattern STEPS = Pattern.compile("steps = \\[(.*)]");
    private static final Pattern VALUES = Pattern.compile("values = \\[(.*)]");

    @Test
    @DisplayName("binary search visits the midpoint of a halving range and ends on the target")
    void binarySearchVisitsMidpoints() {
        // Twelve seconds, so the row is already long enough and nothing is added to it.
        String code = render("Binary search", new AnimationSpec("ARRAY_SEARCH",
                List.of(3, 8, 12, 19, 24, 31, 37, 45, 52, 63, 71, 88), 63, "binary"), 12);

        // Twelve values, so at most four probes; each one names the cell it looked at.
        List<String> steps = steps(code);
        assertThat(steps).hasSize(4);
        assertThat(steps.get(0)).contains("(0, 11, 5,").contains("a[5] = 31 < 63");
        assertThat(steps.get(1)).contains("(6, 11, 8,").contains("a[8] = 52 < 63");
        assertThat(steps.get(2)).contains("(9, 11, 10,").contains("a[10] = 71 > 63");
        // The last step is flagged found, which is what colours the cell green.
        assertThat(steps.get(3)).contains("(9, 9, 9, 1,").contains("a[9] = 63");
    }

    @Test
    @DisplayName("a binary search is given a sorted array even when the model supplies one that is not")
    void binarySearchSortsItsArray() {
        String code = render("Binary search", new AnimationSpec("ARRAY_SEARCH",
                List.of(45, 3, 88, 12, 63, 24, 71, 8), 63, "binary"), 20);

        assertThat(values(code)).containsAll(List.of(3, 8, 12, 24, 45, 63, 71, 88)).isSorted();
    }

    @Test
    @DisplayName("a short row is widened until halving it fills the narration")
    void binarySearchWidensItsArrayForALongNarration() {
        // Eight values is two probes is nine seconds of animation. Under thirty seconds of
        // narration that is a picture which finishes and then sits there — the same defect as the
        // one this rewrite is about, reached from the other direction. Measured on the first film
        // built from the template: nineteen static seconds of a twenty-nine second scene.
        List<Integer> given = List.of(3, 8, 12, 19, 24, 31, 37, 45);
        AnimationSpec spec = new AnimationSpec("ARRAY_SEARCH", given, 31, "binary");

        String brief = render("Binary search", spec, 12);
        String lengthy = render("Binary search", spec, 32);

        assertThat(values(brief)).isEqualTo(given);
        assertThat(values(lengthy)).hasSizeGreaterThan(given.size());
        // Every number the model chose is still there, the row is still sorted, and the target is
        // still in it — the extra cells are bisected into the gaps.
        assertThat(values(lengthy)).containsAll(given).isSorted().contains(31);

        // What actually matters is the trace, not the row: adding cells moves every index after
        // the insertion, and a longer row that lands the target on an early midpoint is a *shorter*
        // animation. That is the regression this line exists for.
        assertThat(steps(lengthy)).hasSizeGreaterThan(steps(brief).size());
    }

    @Test
    @DisplayName("widening stops at the width the frame can hold")
    void wideningHasACeiling() {
        // Sixteen cells is where the row stops being readable at 720p. A narration long enough to
        // ask for more gets a longer hold on the finished picture instead of an unreadable row.
        assertThat(values(render("Binary search", new AnimationSpec("ARRAY_SEARCH",
                List.of(3, 8, 12, 19), 12, "binary"), 300))).hasSizeLessThanOrEqualTo(16);
    }

    @Test
    @DisplayName("a linear search stops at the target rather than walking the whole row")
    void linearSearchStopsWhenFound() {
        String code = render("Linear search", new AnimationSpec("ARRAY_SEARCH",
                List.of(9, 4, 7, 1, 6, 3), 7, "linear"), 20);

        List<String> steps = steps(code);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(2)).contains("a[2] = 7");
        // Unsorted, and left that way: a linear scan does not care.
        assertThat(values(code)).isEqualTo(List.of(9, 4, 7, 1, 6, 3));
    }

    @Test
    @DisplayName("a target the array does not contain is replaced by one it does")
    void targetIsAlwaysReachable() {
        String code = render("Binary search", new AnimationSpec("ARRAY_SEARCH",
                List.of(3, 8, 12, 19), 999, "binary"), 20);

        List<Integer> values = values(code);
        assertThat(code).contains("target = ");
        assertThat(steps(code).getLast()).contains(", 1,");
        assertThat(values).contains(targetOf(code));
    }

    @Test
    @DisplayName("a sort trace ends with the values in order")
    void sortTraceSorts() {
        for (String strategy : List.of("bubble", "selection", "insertion")) {
            String code = render("Sorting", new AnimationSpec("BAR_SORT",
                    List.of(7, 2, 9, 4), null, strategy), 30);
            assertThat(replay(values(code), steps(code)))
                    .describedAs(strategy)
                    .isEqualTo(List.of(2, 4, 7, 9));
        }
    }

    @Test
    @DisplayName("a sort shows fewer bars rather than a trace that stops half way")
    void sortShrinksToFitTheNarration() {
        AnimationSpec spec = new AnimationSpec("BAR_SORT", List.of(7, 2, 9, 4, 6, 1, 8, 5), null, "bubble");

        List<Integer> roomy = values(render("Sorting", spec, 90));
        List<Integer> cramped = values(render("Sorting", spec, 20));

        assertThat(roomy).hasSizeGreaterThan(cramped.size());
        // Whatever it kept, it sorted: the shrunk animation is still true.
        assertThat(replay(cramped, steps(render("Sorting", spec, 20)))).isSorted();
    }

    @Test
    @DisplayName("the animation is paced to last as long as the narration")
    void pacingFillsTheNarration() {
        for (double seconds : List.of(12.0, 20.0, 26.0, 40.0)) {
            for (int steps : List.of(3, 4, 8, 14)) {
                Pacing pacing = ManimTemplates.pace(seconds, 4.65, steps);
                double total = 4.65 + ManimTemplates.SETTLE
                        + steps * (pacing.step() + pacing.pause()) + pacing.tail();

                // The rule the renderer enforces, asserted here so a scene from the library can
                // never be rejected by it: the animation is never under half its narration.
                assertThat(total)
                        .describedAs("%.0fs of narration over %d steps", seconds, steps)
                        .isGreaterThan(seconds * 0.5);
                if (unclamped(pacing)) {
                    // Nothing at a limit means the arithmetic had room to solve exactly, and then
                    // it has to: this is the defect that started the rewrite, an animation that
                    // finishes early and freezes for the rest of the narration.
                    assertThat(total).isCloseTo(seconds, org.assertj.core.data.Offset.offset(1.0));
                }
            }
        }
    }

    // A narration too short for an honest animation runs over instead, and the composer pads it
    // with silence — which is the right way round. Eight beats cannot be shown in four seconds.
    @Test
    @DisplayName("a narration shorter than the animation is padded rather than raced through")
    void shortNarrationDoesNotCompressTheAnimation() {
        Pacing hurried = ManimTemplates.pace(12.0, 4.65, 8);

        assertThat(hurried.step()).isEqualTo(0.75);
        assertThat(hurried.pause()).isEqualTo(0.30);
        assertThat(hurried.tail()).isEqualTo(0.80);
    }

    private static boolean unclamped(Pacing pacing) {
        return pacing.step() > 0.75 && pacing.step() < 3.0
                && pacing.pause() > 0.30 && pacing.pause() < 1.5
                && pacing.tail() > 0.80 && pacing.tail() < 5.0;
    }

    @Test
    @DisplayName("a narration too short for any honest pacing still produces a scene")
    void pacingNeverReturnsNothing() {
        Pacing pacing = ManimTemplates.pace(2.0, 4.65, 9);

        assertThat(pacing.step()).isGreaterThan(0.0);
        assertThat(pacing.pause()).isGreaterThan(0.0);
        assertThat(pacing.tail()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("a template nobody wrote is not an error, it is a scene for the model")
    void unknownTemplateFallsThrough() {
        assertThat(templates.render("Graphs",
                new AnimationSpec("GRAPH_TRAVERSAL", null, null, null), 20)).isEmpty();
        assertThat(templates.render("Anything", null, 20)).isEmpty();
    }

    @Test
    @DisplayName("every placeholder is filled")
    void noPlaceholderSurvives() {
        assertThat(render("Binary search", new AnimationSpec("ARRAY_SEARCH", null, null, "binary"), 25))
                .doesNotContain("{{").doesNotContain("}}");
        assertThat(render("Sorting", new AnimationSpec("BAR_SORT", null, null, "selection"), 25))
                .doesNotContain("{{").doesNotContain("}}");
    }

    @Test
    @DisplayName("a title cannot close the string it is written into")
    void titleCannotEscapeItsQuotes() {
        String code = render("He said \"stop\" \\ {{values}}",
                new AnimationSpec("ARRAY_SEARCH", null, null, "binary"), 25);

        assertThat(code).contains("Text(\"He said stop  values\", font_size=40");
        // The array is still the array, not whatever the title asked for.
        assertThat(values(code)).hasSizeBetween(12, 16);
    }

    @Test
    @DisplayName("values the frame cannot hold are replaced by ones it can")
    void unusableValuesFallBackToTheDefaults() {
        assertThat(values(render("Binary search",
                new AnimationSpec("ARRAY_SEARCH", List.of(5, 7), null, "binary"), 25)))
                .hasSizeBetween(12, 16).containsAll(List.of(3, 8, 12, 19, 24, 31, 37, 45, 52, 63, 71, 88));
        assertThat(values(render("Binary search",
                new AnimationSpec("ARRAY_SEARCH", List.of(-4, 0, -1, 5000), null, "binary"), 25)))
                .hasSizeBetween(12, 16);
        assertThat(values(render("Sorting",
                new AnimationSpec("BAR_SORT", List.of(4, 4, 4, 4, 4), null, "bubble"), 40)))
                .hasSize(6);
    }

    @Test
    @DisplayName("the templates obey the rules the sandbox enforces on generated code")
    void templatesStayInsideTheAllowList() {
        // Not a substitute for the worker's allow-list, which is the authority and runs over this
        // code at render time like any other. It is here because a template that breaks a rule is
        // found in two minutes of Manim and one wasted job, and found here in a second — and
        // because the rules easiest to break while editing these files are all one word long.
        for (String code : List.of(
                render("Binary search", new AnimationSpec("ARRAY_SEARCH", null, null, "binary"), 25),
                render("Sorting", new AnimationSpec("BAR_SORT", null, null, "selection"), 25))) {
            assertThat(code.lines().filter(line -> line.startsWith("import ")
                    || line.startsWith("from ")).toList()).containsExactly("from manim import *");
            assertThat(code.lines().filter(line -> line.startsWith("class ")).toList())
                    .containsExactly("class GeneratedScene(Scene):");
            assertThat(code)
                    .doesNotContain("while ").doesNotContain("lambda ").doesNotContain("with ")
                    .doesNotContain("try:").doesNotContain("__").doesNotContain("@");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private String render(String title, AnimationSpec spec, double seconds) {
        Optional<String> code = templates.render(title, spec, seconds);
        assertThat(code).isPresent();
        assertThat(code.get()).startsWith("from manim import *").contains("class GeneratedScene(Scene):");
        return code.get();
    }

    private static List<String> steps(String code) {
        Matcher matcher = STEPS.matcher(code);
        assertThat(matcher.find()).isTrue();
        return List.of(matcher.group(1).split("(?<=\\)), "));
    }

    private static List<Integer> values(String code) {
        Matcher matcher = VALUES.matcher(code);
        assertThat(matcher.find()).isTrue();
        return List.of(matcher.group(1).split(", ")).stream().map(Integer::valueOf).toList();
    }

    private static int targetOf(String code) {
        Matcher matcher = Pattern.compile("target = (\\d+)").matcher(code);
        assertThat(matcher.find()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    // Replays a sort trace the way bar-sort.py does — every SWAP exchanges two positions — and
    // returns what the viewer is left looking at. If this does not come back sorted, the animation
    // renders a sort that does not sort.
    private static List<Integer> replay(List<Integer> values, List<String> steps) {
        List<Integer> order = new java.util.ArrayList<>(values);
        Pattern move = Pattern.compile("\\((\\d+), (\\d+), 1,");
        for (String step : steps) {
            Matcher matcher = move.matcher(step);
            if (matcher.find()) {
                int left = Integer.parseInt(matcher.group(1));
                int right = Integer.parseInt(matcher.group(2));
                order.set(left, order.set(right, order.get(left)));
            }
        }
        return List.copyOf(order);
    }
}
