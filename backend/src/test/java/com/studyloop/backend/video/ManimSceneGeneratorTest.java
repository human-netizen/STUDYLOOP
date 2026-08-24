package com.studyloop.backend.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Getting the code out of the reply, which sounds like tidying and is not.
//
// A stray fence marker reaching the worker is a syntax error. The sandbox reports it as COMPILE,
// the fix loop then spends a model call asking the model to repair code that was never broken, and
// the scene's budget pays for the round trip. Four lines of parsing here is one fewer billed call
// per scene, every time the model fences its answer — which is most of the time, whatever the
// prompt says.
class ManimSceneGeneratorTest {

    private static final String CODE = """
            from manim import *

            class GeneratedScene(Scene):
                def construct(self):
                    self.play(Write(Text("Hello")))""";

    @Test
    void anUnfencedReplyIsTakenAsWritten() {
        assertEquals(CODE, ManimSceneGenerator.extractCode(CODE));
    }

    @Test
    void aFencedReplyLosesItsFence() {
        String reply = "```python\n" + CODE + "\n```";

        assertEquals(CODE, ManimSceneGenerator.extractCode(reply));
    }

    // Models explain themselves even when told not to. The fence is the reliable marker of where
    // the code starts, not the first line of the reply.
    @Test
    void proseAroundTheFenceIsDropped() {
        String reply = "Here is the scene:\n\n```python\n" + CODE + "\n```\n\nIt fades in the title.";

        String extracted = ManimSceneGenerator.extractCode(reply);

        assertTrue(extracted.startsWith("from manim import *"), extracted);
        assertTrue(extracted.endsWith("self.play(Write(Text(\"Hello\")))"), extracted);
    }

    @Test
    void anEmptyReplyIsEmptyRatherThanNull() {
        assertEquals("", ManimSceneGenerator.extractCode(null));
    }

    // ── the class name ──────────────────────────────────────────────────────────────────────
    //
    // The renderer runs `manim render scene.py GeneratedScene`, so a scene called anything else is
    // rejected before it runs. Measured on a real job: the model called it `BinarySearch`, was shown
    // "The file must define exactly one class, named GeneratedScene", and called it `BinarySearch`
    // again. The animation was lost and three model calls were spent on the spelling of a name.

    @Test
    void aSceneUnderAnotherNameIsRenamed() {
        String reply = CODE.replace("GeneratedScene", "BinarySearch");

        assertEquals(CODE, ManimSceneGenerator.nameScene(reply));
    }

    @Test
    void aSceneAlreadyNamedCorrectlyIsUntouched() {
        assertEquals(CODE, ManimSceneGenerator.nameScene(CODE));
    }

    // Two classes are a shape the sandbox rejects for reasons that have nothing to do with naming,
    // and renaming one of them would only change which complaint comes back.
    @Test
    void twoClassesAreLeftForTheSandboxToJudge() {
        String reply = CODE.replace("GeneratedScene", "First") + "\n\nclass Second(Scene):\n    pass";

        assertEquals(reply, ManimSceneGenerator.nameScene(reply));
    }

    // Only the definition line. An indented `class` is inside something the sandbox does not allow
    // anyway, and a mention of the name in a string or a comment is not a definition.
    @Test
    void onlyTheDefinitionLineIsRewritten() {
        String reply = """
                from manim import *

                class Draft(Scene):
                    def construct(self):
                        self.play(Write(Text("class Draft is the old name")))""";

        String named = ManimSceneGenerator.nameScene(reply);

        assertTrue(named.contains("class GeneratedScene(Scene):"), named);
        assertTrue(named.contains("\"class Draft is the old name\""), named);
    }
}
