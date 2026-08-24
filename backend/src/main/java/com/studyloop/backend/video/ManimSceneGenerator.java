package com.studyloop.backend.video;

import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import com.studyloop.backend.video.VideoPlanner.PlannedScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// One scene's Manim code: taken from the template library when the storyboard named one, and asked
// of the model when it did not.
//
// **The library comes first, and that ordering is the whole of what the first version got wrong.**
// It asked the model to invent an animation from a sentence every time, and the animations it
// invented were tiny — measured on the first watchable film, the drawing occupied 0.6% of the
// frame. ZenLearn keeps a dictionary of parameterised scenes and only falls through to generation;
// this now does the same, and a scene that hits the library costs zero model calls, cannot fail the
// allow-list, and is paced to the narration by arithmetic rather than by asking nicely.
//
// **The prompt states the sandbox's rules, and the sandbox does not trust the prompt.** Those are
// two separate mechanisms doing the same job on purpose. Telling the model "no imports" makes the
// common case work — a cooperative model writing ordinary animation code — and the AST allow-list
// in the worker is what happens when the prompt is not enough, whether because the model
// misunderstood, because the retrieved material contained something that reads like an
// instruction, or because somebody found a way to put words in the model's mouth. A prompt is a
// request. Only the allow-list is a rule.
//
// **The fix loop is ZenLearn's idea, bounded.** Feeding the compiler's own message back is what
// makes generated code work often enough to be worth attempting: most failures are a renamed
// Manim method or a missing argument, and the model fixes those in one turn. What is not ported is
// the retry policy around it — three attempts with a fresh 120-second timeout each and no overall
// budget, which is how a job spends twenty minutes and still produces slides. Here the attempts
// share one wall-clock budget per scene, and when it is gone the scene is a slide.
@Component
@RequiredArgsConstructor
public class ManimSceneGenerator {

    private final ChatClient chatClient;
    private final ManimTemplates templates;

    // The template library's answer, when it has one. Empty means the caller should ask the model.
    //
    // Separate from generate() rather than folded into it because the runner counts model calls
    // onto the scene row, and a scene drawn from the library made none — a fallback report where
    // "0 calls, animated" and "1 call, animated" look the same cannot say what the library is
    // worth.
    public Optional<String> fromTemplate(PlannedScene scene, double spokenSeconds) {
        return templates.render(scene.title(), scene.animation(), spokenSeconds);
    }

    // First attempt. The narration is included as well as the visual description, and so is its
    // measured length: the animation has to still be moving when the sentence ends, and a model
    // that has not been told how long the sentence is will write a twelve-second build under half
    // a minute of speech.
    public String generate(PlannedScene scene, double spokenSeconds) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.user("""
                        Scene title: %s

                        The narrator speaks for %s seconds over this scene. Your animation has to \
                        last that long — that is the single most important thing about it.

                        What the narrator says over it:
                        %s

                        What the animation should show:
                        %s
                        """.formatted(
                        scene.title(),
                        seconds(spokenSeconds),
                        scene.narration(),
                        scene.visualDescription() == null
                                ? "Illustrate the narration above."
                                : scene.visualDescription())));
        return nameScene(extractCode(call(messages)));
    }

    // A retry that has seen the failure. The previous code goes back verbatim rather than as a
    // summary: the model needs the line the error refers to, and paraphrasing an error into "it
    // did not compile" is the difference between a fix and another guess.
    public String fix(String previousCode, String failure, double spokenSeconds) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.user("""
                        This scene was rejected. Here is the code you wrote:

                        ```python
                        %s
                        ```

                        Here is what went wrong:

                        %s

                        Rewrite the whole file so it does not fail this way. The narration over \
                        this scene is %s seconds long and the animation still has to last that \
                        long. Keep it simple — a scene that renders is better than a scene that is \
                        clever. Reply with the complete corrected file and nothing else.
                        """.formatted(previousCode, truncate(failure), seconds(spokenSeconds))));
        return nameScene(extractCode(call(messages)));
    }

    private static String seconds(double value) {
        return String.format(Locale.ROOT, "%.0f", Math.max(1.0, value));
    }

    // The renderer imports the file and looks for a class called GeneratedScene. The prompt says so
    // twice, in a shape block and in a rule, and the model still calls it `BinarySearch` — and then
    // calls it `BinarySearch` again on the fix attempt, having been shown the rejection verbatim.
    // Measured: one animation lost, three model calls spent, on the spelling of a name.
    //
    // **Renaming it here is the same trade the fence-stripping above makes.** The file defines
    // exactly one scene, its name is not part of what the model was asked to decide, and nothing
    // downstream reads it except the `manim render` argument. Two lines of parsing beat a rejection
    // the model demonstrably cannot act on.
    //
    // Only ever one class, and only ever the definition line: two classes are a shape the sandbox
    // rejects for reasons that have nothing to do with naming, and quietly editing more than the
    // one token would be rewriting the model's code rather than addressing it.
    static String nameScene(String code) {
        if (code == null) {
            return "";
        }
        Matcher classes = SCENE_CLASS.matcher(code);
        if (!classes.find()) {
            return code;
        }
        int start = classes.start(1);
        int end = classes.end(1);
        if (code.substring(start, end).equals(SCENE_CLASS_NAME) || classes.find()) {
            return code;
        }
        return code.substring(0, start) + SCENE_CLASS_NAME + code.substring(end);
    }

    private static final String SCENE_CLASS_NAME = "GeneratedScene";
    private static final Pattern SCENE_CLASS = Pattern.compile("^class\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);

    private String call(List<LlmMessage> messages) {
        try (var ignored = AiUsageContext.of(AiOperation.VIDEO_SCENE_CODE)) {
            return chatClient.complete(messages);
        }
    }

    // Models fence code even when told not to, and they fence it inconsistently. Strip one fence
    // if there is one, and otherwise take the reply as written — a stray fence marker reaching the
    // worker would be a syntax error, which the sandbox reports as COMPILE and the fix loop then
    // spends a model call correcting. Four lines here saves that call.
    static String extractCode(String reply) {
        if (reply == null) {
            return "";
        }
        String text = reply.strip();
        int fence = text.indexOf("```");
        if (fence < 0) {
            return text;
        }
        int start = text.indexOf('\n', fence);
        int end = text.lastIndexOf("```");
        if (start < 0 || end <= start) {
            return text;
        }
        return text.substring(start + 1, end).strip();
    }

    private static String truncate(String failure) {
        if (failure == null) {
            return "(no output)";
        }
        // Manim tracebacks run to hundreds of lines and the useful part is the end. Sending the
        // whole thing costs tokens to bury the message in.
        String trimmed = failure.strip();
        return trimmed.length() <= 2000 ? trimmed : "…\n" + trimmed.substring(trimmed.length() - 2000);
    }

    // The rules the allow-list enforces, written as a request, and then the rules nothing enforces
    // except the person watching.
    //
    // **The second half is new and it is the half that was missing.** The first version of this
    // prompt was thirty lines of prohibitions and one sentence about pacing, and it produced
    // exactly what a list of prohibitions produces: code that breaks no rule and shows nothing —
    // a row of small numbers along the bottom edge, finished in twelve seconds, under half a
    // minute of narration. Ending with a complete scene worth copying does more than any number of
    // further prohibitions, which is the one piece of ZenLearn's much shorter prompt worth taking
    // wholesale.
    private static final String SYSTEM_PROMPT = """
            You write Manim Community Edition scenes for a study video. Reply with one Python file \
            and nothing else — no explanation, no markdown fence.

            The file must be exactly this shape:

                from manim import *

                class GeneratedScene(Scene):
                    def construct(self):
                        ...

            Hard rules. Code that breaks any of them is rejected before it runs, and the scene \
            becomes a plain slide instead:
            - **This renderer has no LaTeX, so `Text` is the only way to put words, numbers or \
            mathematics on screen.** Write `Text("O(n log n)")`, `Text("5")`, `Text("n / 2")` — \
            the formula as it is read aloud. These are rejected and there is no way around them: \
            `MathTex`, `Tex`, `Integer`, `DecimalNumber`, `Variable`, `Matrix`, `Title`, \
            `BulletedList`. So are `add_coordinates()`, `get_axis_labels()`, `add_numbers()` and \
            `Brace.get_text()`, which build their labels out of LaTeX. `Axes`, `NumberLine`, \
            `NumberPlane`, `Table`, `Brace` and every shape are fine.
            - `from manim import *` is the only import. No `import os`, no `import numpy`, no \
            imports of any other kind.
            - No `open`, `eval`, `exec`, `compile`, `input`, `__import__`, `getattr`, `setattr`, \
            `globals`, `locals`, or `vars`.
            - No names starting with two underscores, and no attribute access to them.
            - No decorators, no `with`, no `try`, no `while`, no `lambda`. A `for` over `range` or \
            over a list is how you loop.
            - No file paths, no URLs, no `SVGMobject`, no `ImageMobject`: nothing that reads from \
            disk or the network. Everything on screen is drawn from shapes and text.

            Now the part that decides whether the scene is any good. A scene can pass every rule \
            above and still be thrown away for these, so read them as rules too:

            **Fill the frame.** It is 14.2 wide and 8 tall with the origin at the centre. Your \
            drawing has to span at least half of it in each direction. A row of small text near one \
            edge is the commonest way this fails: boxes are `Square(side_length=1.0)`, not 0.2, and \
            a group of them gets `.set_width(11)` and `.move_to(ORIGIN)` so it uses the width it \
            has. Position with `.to_edge()`, `.next_to()` and `.shift()`, and keep everything \
            inside the frame — `.to_edge(UP)` is already against the top, so shifting up again puts \
            it where nothing renders.

            **Open with a title.** `Text(...)` at `font_size=40`, `.to_edge(UP, buff=0.5)`, \
            written on with `Write`, and left there for the whole scene. The viewer should be able \
            to pause anywhere and know what they are looking at.

            **Last exactly as long as the narration.** The request tells you how many seconds the \
            voice runs for. Add up your `run_time`s and `wait`s and make them come to that number. \
            An animation that finishes early is not padded — the last frame is frozen for the rest \
            of the narration, and a film that races through the idea and then stops dead while \
            somebody is still explaining it is the single worst thing this can do. Break the \
            narration into its steps and give each step its own beat of about two seconds, then \
            spend what is left holding the finished picture.

            **Show one thing at a time, and leave the result up.** Build with `Write`, `Create`, \
            `FadeIn`, `GrowFromCenter`; change with `.animate` — `self.play(box.animate.shift(\
            RIGHT))`, `self.play(label.animate.set_color(YELLOW))`. Passing a method and its \
            arguments to `self.play` was removed from Manim years ago and raises a TypeError. \
            Nothing appears until an animation puts it there. Fade out the working parts as you \
            finish with them, but end with the finished picture still on screen.

            Here is a complete scene of the standard this is held to. Copy its shape:

                from manim import *

                class GeneratedScene(Scene):
                    def construct(self):
                        title = Text("Pushing onto a stack", font_size=40, color="#e7edf2")
                        title.to_edge(UP, buff=0.5)
                        rule = Line(LEFT, RIGHT, color="#26c9c0", stroke_width=4)
                        rule.set_width(title.width + 0.5)
                        rule.next_to(title, DOWN, buff=0.2)
                        self.play(Write(title), run_time=1.0)
                        self.play(Create(rule), run_time=0.4)

                        floor = Line(LEFT * 2.4, RIGHT * 2.4, color="#2f3a44", stroke_width=4)
                        floor.shift(DOWN * 2.6)
                        note = Text("push 3, push 9, push 7", font_size=28, color="#5b6976")
                        note.next_to(floor, DOWN, buff=0.6)
                        self.play(Create(floor), FadeIn(note), run_time=0.8)

                        plates = VGroup()
                        for value in [3, 9, 7]:
                            box = Rectangle(width=3.6, height=1.0, stroke_width=3,
                                            color="#26c9c0", fill_color="#161b1f",
                                            fill_opacity=1.0)
                            label = Text(str(value), font_size=30, color="#e7edf2")
                            label.move_to(box.get_center())
                            plates.add(VGroup(box, label))
                        plates.arrange(UP, buff=0.2)
                        plates.next_to(floor, UP, buff=0.1)

                        for index in range(3):
                            plates[index].shift(UP * 6.0)
                            self.play(plates[index].animate.shift(DOWN * 6.0), run_time=1.4)
                            self.wait(0.8)

                        arrow = Arrow(RIGHT * 2.0, ORIGIN, color="#f2b134", buff=0.0)
                        arrow.next_to(plates[2], RIGHT, buff=0.25)
                        crown = Text("top", font_size=26, color="#f2b134")
                        crown.next_to(arrow, RIGHT, buff=0.2)
                        self.play(GrowArrow(arrow), FadeIn(crown), run_time=0.9)
                        self.wait(2.5)
            """;
}
