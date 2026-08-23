package com.studyloop.backend.retrieval;

import com.studyloop.backend.document.StubAiConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// One Spring context for both of Phase 18's integration tests, and it is a composed annotation
// rather than two copies of the same five lines because copies drift.
//
// **The constraint is a hard limit, not tidiness.** Spring caches an ApplicationContext per distinct
// configuration, and every context opens its own Hikari pool against Supabase's session pooler,
// which allows fifteen clients in total. Two test classes with two different `properties` arrays are
// two contexts and two pools, and the suite pays for it somewhere else entirely — the first run of
// these two classes took the count over the line and ReviewQueueTest, which touches none of this,
// failed to start with `FATAL: (EMAXCONNSESSION) max clients reached in session mode`. StubAiConfig
// already carries the same warning for the three document test classes; this is the same rule
// applied to the two written here.
//
// Both stages are on, so each class runs with the other's stage enabled. That is deliberate rather
// than tolerated: it is nearer the shipping pipeline than either alone, and neither class's
// assertions depend on the other stage being off — the trigram list carries no cosine, so it cannot
// move the gate signal HydeRetrievalTest measures, and the default stub expansion parses to nothing,
// so HyDE contributes no list under TrigramRetrievalTest.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(properties = {
        "studyloop.retrieval.stages.trigram=true",
        "studyloop.retrieval.stages.hyde=true",
        // Pinned rather than inherited: the stub embedder puts unrelated strings in near-orthogonal
        // directions, so a first pass here always looks weak. If the shipped default later moved
        // above or below that, these tests would quietly stop exercising the second pass.
        "studyloop.retrieval.hyde.trigger-similarity=0.45"
})
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
public @interface QueryUnderstandingTest {
}
