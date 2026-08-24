package com.studyloop.backend.video;

import com.studyloop.backend.video.VideoPlanner.AnimationSpec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// The animations this product knows how to draw without asking a model to invent them.
//
// **This is ZenLearn's idea and it is the one that decides whether the video is watchable.** Its
// pipeline keeps a small dictionary of parameterised Manim scenes and fills one in when the topic
// fits; only the leftovers go to the model as free-form code generation. The first version of this
// feature had no such dictionary, and the difference is not subtle — measured on the two files, the
// animation a model wrote unaided put its drawing inside 0.6% of the frame, a row of 30-pixel
// numbers along the bottom edge, while ZenLearn's reference render fills 52%. A prompt cannot
// reliably ask for composition. A template already has it.
//
// **The steps are computed here, in Java, and inlined as a literal.** The obvious alternative is a
// template that runs the algorithm itself, which is what ZenLearn's `binary_search` template does —
// and it cannot work here, because the sandbox's allow-list has no `while`. Precomputing also buys
// the thing the sandbox cannot: the step count is known *before* the render, so the pacing can be
// solved against the narration's measured length instead of guessed at.
//
// **Nothing here skips the sandbox.** The filled template is posted through the same endpoint as
// generated code and checked by the same allow-list. That is deliberate: a trusted path around the
// checker is how the checker stops being true, and these files are cheap to keep inside the rules.
@Component
public class ManimTemplates {

    // Fixed animation cost of each template, in seconds: the title, the build, the closing beat.
    // Everything else is per-step and is what the pacing solves for.
    private static final double SEARCH_OVERHEAD = 3.45 + 0.4 + 0.8;
    private static final double SORT_OVERHEAD = 3.35 + 0.8 + 0.8;

    // A sort step is two plays, not one: a compare flashes the pair amber and then puts it back, a
    // swap lifts the bars over each other and then sets them down. The second half is a fixed cost
    // the pacing has to know about, or the animation overruns its narration by the sum of them —
    // measured at 26.4 seconds against 24 before this was subtracted.
    private static final double COMPARE_RESET = 0.20;
    private static final double SWAP_SETTLE = 0.25;

    // A step slower than this is a slideshow; a step faster than this cannot be read.
    private static final double MIN_STEP = 0.75;
    private static final double MAX_STEP = 3.00;
    private static final double MIN_PAUSE = 0.30;
    private static final double MAX_PAUSE = 1.50;
    private static final double MIN_TAIL = 0.80;
    private static final double MAX_TAIL = 5.00;
    static final double SETTLE = 0.70;

    private static final List<Integer> DEFAULT_SEARCH_VALUES =
            List.of(3, 8, 12, 19, 24, 31, 37, 45, 52, 63, 71, 88);
    private static final List<Integer> DEFAULT_SORT_VALUES = List.of(7, 2, 9, 4, 6, 1);

    private static final int MIN_ITEMS = 4;
    private static final int MAX_SEARCH_ITEMS = 16;
    private static final int MAX_SORT_ITEMS = 8;

    // Templates are read once and held. They are a few kilobytes each and are read on the render
    // path, where the alternative is a file read inside a per-scene loop.
    private final Map<String, String> sources = new ConcurrentHashMap<>();

    // Empty when this scene is not one of the shapes below, which is the ordinary case and not a
    // failure — the caller falls through to asking the model for code.
    //
    // `seconds` is the narration's *measured* length, not an estimate: the runner has already
    // synthesised the audio by the time it gets here.
    public Optional<String> render(String title, AnimationSpec spec, double seconds) {
        if (spec == null || spec.template() == null) {
            return Optional.empty();
        }
        return switch (spec.template().trim().toUpperCase(Locale.ROOT)) {
            case "ARRAY_SEARCH" -> Optional.of(arraySearch(title, spec, seconds));
            case "BAR_SORT" -> sort(title, spec, seconds);
            default -> Optional.empty();
        };
    }

    // ── array search ────────────────────────────────────────────────────────────────────────

    // A row of boxed values, an index under each, and a pointer that moves. The searched-out part
    // of the row dims as the region narrows, which is the whole idea of binary search rendered as
    // a picture rather than described in a sentence.
    private String arraySearch(String title, AnimationSpec spec, double seconds) {
        boolean linear = "LINEAR".equalsIgnoreCase(spec.strategy());
        List<Integer> values = new ArrayList<>(
                clamp(spec.values(), DEFAULT_SEARCH_VALUES, MIN_ITEMS, MAX_SEARCH_ITEMS));
        if (!linear) {
            // Binary search over unsorted values is not binary search. A model that supplies an
            // unsorted array has named the wrong array, not the wrong algorithm.
            //
            // Sorted before the target is chosen, not after: chooseTarget falls back to a value
            // three quarters of the way along the row, and three quarters of an unsorted row is
            // nowhere in particular — often the midpoint, which ends the search in one probe.
            values.sort(Integer::compareTo);
        }
        int target = chooseTarget(spec.target(), values);
        if (!linear) {
            target = widen(values, target, comfortableSteps(seconds, SEARCH_OVERHEAD));
        }

        List<String> steps = linear ? linearSteps(values, target) : binarySteps(values, target);
        Pacing pacing = pace(seconds, SEARCH_OVERHEAD, steps.size());

        return fill("array-search.py", Map.of(
                "title", python(title),
                "values", pythonList(values),
                "goal", python("target = " + target),
                "opening", python(linear ? "scan from the left" : "search the whole array"),
                "steps", pythonSteps(steps),
                "settle", number(SETTLE),
                "stepTime", number(pacing.step()),
                "stepPause", number(pacing.pause()),
                "tail", number(pacing.tail())));
    }

    // Grows the array until halving it takes long enough to fill the narration, and returns the
    // target to search for.
    //
    // **A binary search over eight values is two probes, and two probes is nine seconds.** Under
    // thirty seconds of narration that is a picture which finishes and then sits there — the exact
    // defect this rewrite is about, arrived at from the other direction, and measured at nineteen
    // static seconds of a twenty-nine second scene. The step count is `log2(n)`, so the only dial
    // that adds beats is the length of the row.
    //
    // **It widens against the measured trace and not against the row's length**, because the two
    // are not the same question. Inserting a value moves every index after it, so a longer row can
    // put the target *on* an early midpoint and make the search shorter — which is how the first
    // version of this turned a four-probe animation into a two-probe one while adding four cells.
    //
    // The model's own numbers are all kept: new ones are bisected into the widest gap, so the row
    // stays sorted, stays plausible, and still contains the target. Sixteen cells is where it stops
    // being readable at 720p, which is the real ceiling here rather than the arithmetic.
    private static int widen(List<Integer> values, int target, int wantedSteps) {
        while (values.size() < MAX_SEARCH_ITEMS && binarySteps(values, target).size() < wantedSteps) {
            bisectWidestGap(values);
        }
        // A target sitting on the first midpoint ends the search in one step whatever the row's
        // length, and a one-step binary search demonstrates nothing. Only then is the model's
        // number overridden, and only by one that is already in the array.
        if (binarySteps(values, target).size() >= Math.min(3, wantedSteps)) {
            return target;
        }
        int best = target;
        int longest = binarySteps(values, target).size();
        for (int candidate : values) {
            int length = binarySteps(values, candidate).size();
            if (length > longest) {
                longest = length;
                best = candidate;
            }
        }
        return best;
    }

    private static void bisectWidestGap(List<Integer> values) {
        int widest = 0;
        for (int i = 1; i < values.size() - 1; i++) {
            if (values.get(i + 1) - values.get(i) > values.get(widest + 1) - values.get(widest)) {
                widest = i;
            }
        }
        int gap = values.get(widest + 1) - values.get(widest);
        if (gap < 2) {
            // No room to bisect anywhere: extend past the end instead, by the average spacing.
            int span = values.getLast() - values.getFirst();
            values.add(values.getLast() + Math.max(1, span / Math.max(1, values.size() - 1)));
        } else {
            values.add(widest + 1, values.get(widest) + gap / 2);
        }
    }

    // (low, high, focus, found, caption) — exactly what one frame of the animation needs, and
    // nothing the scene has to work out for itself.
    private static List<String> binarySteps(List<Integer> values, int target) {
        List<String> steps = new ArrayList<>();
        int low = 0;
        int high = values.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int value = values.get(mid);
            String caption = "a[" + mid + "] = " + value + comparison(value, target);
            steps.add(tuple(low, high, mid, value == target ? 1 : 0, caption));
            if (value == target) {
                break;
            }
            if (value < target) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return steps;
    }

    private static List<String> linearSteps(List<Integer> values, int target) {
        List<String> steps = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            int value = values.get(index);
            String caption = "a[" + index + "] = " + value + comparison(value, target);
            steps.add(tuple(0, values.size() - 1, index, value == target ? 1 : 0, caption));
            if (value == target) {
                break;
            }
        }
        return steps;
    }

    // Nothing at all when the two are equal, because the caption already reads `a[9] = 63` and
    // `a[9] = 63 = 63` is how an expression written by a machine looks.
    private static String comparison(int value, int target) {
        if (value == target) {
            return "";
        }
        return (value < target ? " < " : " > ") + target;
    }

    // The target the model asked for when the array actually contains it, and otherwise one that
    // it does contain. A search that ends in "not found" is a correct animation of a real case, but
    // it is not the one a scene of narration introducing the idea is describing.
    private static int chooseTarget(Integer requested, List<Integer> values) {
        if (requested != null && values.contains(requested)) {
            return requested;
        }
        return values.get(Math.min(values.size() - 1, values.size() * 3 / 4));
    }

    // ── bar sort ────────────────────────────────────────────────────────────────────────────

    // Bars whose heights are the values, compared in amber, swapped by moving over one another,
    // locked green as they reach their place.
    //
    // **The array is shrunk until its trace fits the narration**, rather than the trace being cut
    // to fit. A sort animation that stops in the middle is worse than the same sort over four
    // values, because the second one is true.
    private Optional<String> sort(String title, AnimationSpec spec, double seconds) {
        List<Integer> requested = clamp(spec.values(), DEFAULT_SORT_VALUES, MIN_ITEMS, MAX_SORT_ITEMS);
        int budget = stepBudget(seconds, SORT_OVERHEAD);
        for (int size = requested.size(); size >= MIN_ITEMS; size--) {
            List<Integer> values = requested.subList(0, size);
            List<SortStep> steps = sortSteps(values, spec.strategy());
            if (steps.size() <= budget) {
                Pacing pacing = pace(seconds - trailingPlays(steps), SORT_OVERHEAD, steps.size());
                return Optional.of(fill("bar-sort.py", Map.of(
                        "title", python(title),
                        "values", pythonList(values),
                        "opening", python(strategyName(spec.strategy()) + " sort"),
                        "steps", pythonSteps(steps.stream().map(SortStep::python).toList()),
                        "settle", number(SETTLE),
                        "stepTime", number(pacing.step()),
                        "stepPause", number(pacing.pause()),
                        "tail", number(pacing.tail()))));
            }
        }
        // Four values whose trace still does not fit means the narration is shorter than the
        // shortest honest sort. The model gets the scene instead.
        return Optional.empty();
    }

    // COMPARE flashes the two bars, SWAP moves them past each other, LOCK turns the left one
    // green for good.
    private static List<SortStep> sortSteps(List<Integer> values, String strategy) {
        int[] items = values.stream().mapToInt(Integer::intValue).toArray();
        return switch (strategyName(strategy)) {
            case "selection" -> selectionSteps(items);
            case "insertion" -> insertionSteps(items);
            default -> bubbleSteps(items);
        };
    }

    private static List<SortStep> bubbleSteps(int[] items) {
        List<SortStep> steps = new ArrayList<>();
        for (int pass = 0; pass < items.length - 1; pass++) {
            for (int index = 0; index < items.length - 1 - pass; index++) {
                int left = items[index];
                int right = items[index + 1];
                steps.add(new SortStep(index, index + 1, SortKind.COMPARE,
                        "a[" + index + "] = " + left + comparison(left, right)));
                if (left > right) {
                    items[index] = right;
                    items[index + 1] = left;
                    steps.add(new SortStep(index, index + 1, SortKind.SWAP,
                            "a[" + index + "] <-> a[" + (index + 1) + "]"));
                }
            }
            int settled = items.length - 1 - pass;
            steps.add(new SortStep(settled, settled, SortKind.LOCK,
                    "a[" + settled + "] = " + items[settled]));
        }
        return steps;
    }

    private static List<SortStep> selectionSteps(int[] items) {
        List<SortStep> steps = new ArrayList<>();
        for (int position = 0; position < items.length - 1; position++) {
            int smallest = position;
            for (int index = position + 1; index < items.length; index++) {
                if (items[index] < items[smallest]) {
                    smallest = index;
                }
            }
            steps.add(new SortStep(position, smallest, SortKind.COMPARE,
                    "smallest of a[" + position + "..] = " + items[smallest]));
            if (smallest != position) {
                int held = items[position];
                items[position] = items[smallest];
                items[smallest] = held;
                steps.add(new SortStep(position, smallest, SortKind.SWAP,
                        "a[" + position + "] <-> a[" + smallest + "]"));
            }
            steps.add(new SortStep(position, position, SortKind.LOCK,
                    "a[" + position + "] = " + items[position]));
        }
        return steps;
    }

    private static List<SortStep> insertionSteps(int[] items) {
        List<SortStep> steps = new ArrayList<>();
        for (int index = 1; index < items.length; index++) {
            for (int position = index; position > 0; position--) {
                int left = items[position - 1];
                int right = items[position];
                steps.add(new SortStep(position - 1, position, SortKind.COMPARE,
                        "a[" + (position - 1) + "] = " + left + comparison(left, right)));
                if (left <= right) {
                    break;
                }
                items[position - 1] = right;
                items[position] = left;
                steps.add(new SortStep(position - 1, position, SortKind.SWAP,
                        "a[" + (position - 1) + "] <-> a[" + position + "]"));
            }
        }
        return steps;
    }

    // The fixed second half of every compare and every swap, which the scene spends whatever the
    // pacing decides about the first half. A lock has no second half.
    private static double trailingPlays(List<SortStep> steps) {
        double total = 0.0;
        for (SortStep step : steps) {
            total += switch (step.kind()) {
                case SWAP -> SWAP_SETTLE;
                case COMPARE -> COMPARE_RESET;
                case LOCK -> 0.0;
            };
        }
        return total;
    }

    // One beat of a sort: which two bars, what happens to them, and the line under the chart.
    //
    // A record rather than a formatted string because the pacing has to ask what kind of step it
    // is. The first version asked by looking for an arrow in the caption, which counted every lock
    // as a compare and put the arithmetic back out by a fifth of a second per pass.
    private record SortStep(int left, int right, SortKind kind, String caption) {

        String python() {
            return tuple(left, right, kind.ordinal(), caption);
        }
    }

    // The order is the contract with bar-sort.py, which switches on the number.
    private enum SortKind { COMPARE, SWAP, LOCK }

    private static String strategyName(String strategy) {
        if (strategy == null) {
            return "bubble";
        }
        String named = strategy.trim().toLowerCase(Locale.ROOT);
        return switch (named) {
            case "selection", "insertion", "bubble" -> named;
            default -> "bubble";
        };
    }

    // ── pacing ──────────────────────────────────────────────────────────────────────────────

    // How long each beat runs so the animation lasts as long as the voice.
    //
    // **This is the fix for the defect that started the rewrite.** The first version told the model
    // "about 8 to 15 seconds" no matter what, so a twelve-second animation played under
    // thirty-two seconds of narration and the composer held the final frame for the other twenty.
    // From the outside that is a video that races through binary search while the narrator is still
    // introducing it, and then stops dead.
    //
    // Three dials in order of preference: make each step longer, then hold longer between steps,
    // then linger on the finished picture. All three are clamped, because an animation stretched
    // far enough to fill any narration is a still image with extra steps.
    static Pacing pace(double seconds, double overhead, int steps) {
        if (steps <= 0) {
            return new Pacing(MIN_STEP, MIN_PAUSE, MIN_TAIL);
        }
        double spare = Math.max(0.0, seconds - overhead - SETTLE);
        double step = bound(spare / steps - MIN_PAUSE, MIN_STEP, MAX_STEP);
        spare -= step * steps;
        double pause = bound(spare / steps, MIN_PAUSE, MAX_PAUSE);
        spare -= pause * steps;
        return new Pacing(step, pause, bound(spare, MIN_TAIL, MAX_TAIL));
    }

    // The most steps that fit, used to decide how many values a sort may show.
    private static int stepBudget(double seconds, double overhead) {
        return (int) Math.max(3, (seconds - overhead - SETTLE) / (MIN_STEP + MIN_PAUSE));
    }

    // How many steps *fill* the narration rather than merely fit inside it.
    //
    // The other budget divides by the fastest honest beat, which answers "how many can I show".
    // This one divides by a beat somebody can follow, which answers "how many do I need so the
    // picture is still moving when the sentence ends". A sort uses the first to decide how much to
    // drop; a search uses this one to decide how much to add.
    private static int comfortableSteps(double seconds, double overhead) {
        return (int) Math.max(2, (seconds - overhead - SETTLE) / (WATCHABLE_STEP + WATCHABLE_PAUSE));
    }

    private static final double WATCHABLE_STEP = 2.20;
    private static final double WATCHABLE_PAUSE = 0.80;

    record Pacing(double step, double pause, double tail) { }

    private static double bound(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    // ── filling ─────────────────────────────────────────────────────────────────────────────

    private String fill(String name, Map<String, String> values) {
        String source = sources.computeIfAbsent(name, ManimTemplates::read);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            source = source.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return source;
    }

    private static String read(String name) {
        try {
            return new String(new ClassPathResource("manim/" + name).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A template missing from the jar is a packaging fault, not a runtime condition to
            // degrade around. Failing loudly here is what makes the startup test worth having.
            throw new UncheckedIOException("Manim template " + name + " is missing", e);
        }
    }

    // Values the model supplied, made safe to draw: positive, whole, deduplicated in order, and
    // within a size the frame can hold. Anything unusable falls back to the default array rather
    // than to no animation — a scene about binary search illustrated with a stock array is still a
    // correct illustration of binary search.
    private static List<Integer> clamp(List<Integer> values, List<Integer> fallback, int min, int max) {
        List<Integer> usable = values == null ? List.of() : values.stream()
                .filter(value -> value != null && value > 0 && value <= 999)
                .distinct()
                .limit(max)
                .toList();
        return usable.size() >= min ? usable : fallback.stream().limit(max).toList();
    }

    // ── python literals ─────────────────────────────────────────────────────────────────────

    private static String tuple(Object... parts) {
        return Arrays.stream(parts)
                .map(part -> part instanceof String text ? python(text) : String.valueOf(part))
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private static String pythonSteps(List<String> steps) {
        return "[" + String.join(", ", steps) + "]";
    }

    private static String pythonList(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // A double-quoted Python string with nothing in it that can end the string or start an escape.
    // Titles come from a model and travel into a file that is executed, so the conservative move is
    // to delete the characters that matter rather than to escape them — a scene title has no need
    // of a backslash, and the allow-list would reject what one could build anyway.
    private static String python(String text) {
        if (text == null) {
            return "\"\"";
        }
        StringBuilder safe = new StringBuilder("\"");
        text.strip().codePoints()
                .filter(point -> point != '"' && point != '\\' && point != '\n' && point != '\r'
                        && point != '{' && point != '}')
                .limit(70)
                .forEach(safe::appendCodePoint);
        return safe.append('"').toString();
    }
}
