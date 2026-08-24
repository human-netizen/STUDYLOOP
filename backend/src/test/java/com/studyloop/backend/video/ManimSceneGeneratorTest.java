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
}
