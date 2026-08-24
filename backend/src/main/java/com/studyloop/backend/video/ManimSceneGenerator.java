package com.studyloop.backend.video;

import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import com.studyloop.backend.video.VideoPlanner.PlannedScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// Asks the model for one scene's Manim code, and asks it again when the toolchain rejects what it
// wrote.
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

    // First attempt. The narration is included as well as the visual description, because the
    // animation has to finish roughly when the sentence does — a model that has not been told what
    // is being said will happily write a twelve-second build for a four-second line.
    public String generate(PlannedScene scene) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.user("""
                        Scene title: %s

                        What the narrator says over it:
                        %s

                        What the animation should show:
                        %s
                        """.formatted(
                        scene.title(),
                        scene.narration(),
                        scene.visualDescription() == null
                                ? "Illustrate the narration above."
                                : scene.visualDescription())));
        return extractCode(call(messages));
    }

    // A retry that has seen the failure. The previous code goes back verbatim rather than as a
    // summary: the model needs the line the error refers to, and paraphrasing an error into "it
    // did not compile" is the difference between a fix and another guess.
    public String fix(String previousCode, String failure) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.user("""
                        This scene was rejected. Here is the code you wrote:

                        ```python
                        %s
                        ```

                        Here is what went wrong:

                        %s

                        Rewrite the whole file so it does not fail this way. Keep it simple — a \
                        scene that renders is better than a scene that is clever. Reply with the \
                        complete corrected file and nothing else.
                        """.formatted(previousCode, truncate(failure))));
        return extractCode(call(messages));
    }

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

    // The rules the allow-list enforces, written as a request. Every prohibition here has a
    // matching check in the worker; the wording is deliberately concrete about *what to write*
    // rather than only about what not to, because a prompt that is a list of prohibitions produces
    // code that is a list of workarounds.
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
            - `from manim import *` is the only import. No `import os`, no `import numpy`, no \
            imports of any other kind.
            - No `open`, `eval`, `exec`, `compile`, `input`, `__import__`, `getattr`, `setattr`, \
            `globals`, `locals`, or `vars`.
            - No names starting with two underscores, and no attribute access to them.
            - No decorators, no `with`, no `try`, no `while`, no `lambda`.
            - No file paths, no URLs, no `SVGMobject`, no `ImageMobject`: nothing that reads from \
            disk or the network. Everything on screen is drawn from shapes, text and formulas.

            Style rules, which matter as much because the scene has to be watchable:
            - Use `Text` for words. Use `MathTex` only for actual mathematics, and keep the LaTeX \
            to symbols, fractions, sums and Greek letters.
            - The frame is 14.2 by 8 units with the origin at the centre. Keep everything inside \
            it: position with `.to_edge()`, `.next_to()` and `.shift()`, and scale text down when \
            there is a lot of it.
            - The whole scene must run in about 8 to 15 seconds of animation. Use `self.play(...)` \
            with `run_time` and `self.wait(...)` to pace it.
            - Fade or remove what you are done with. A scene that piles objects on top of each \
            other reads as broken.
            """;
}
