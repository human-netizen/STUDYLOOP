package com.studyloop.backend.video;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.ConfidenceGate;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.config.VideoProperties;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.retrieval.RetrievalResult;
import com.studyloop.backend.retrieval.RetrievalService;
import com.studyloop.backend.retrieval.RetrievedChunk;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Phase 21.2 — turning a topic into a plan, or into a refusal that cost one embedding.
//
// **The video is a rendering of the corpus, not a restatement of it.** AddNewFeature.md §4's
// fourth objection was that a generated video is a narrated slideshow saying what the PDF already
// says, only worse and without sources. Everything in this class is the answer to that: the script
// is written from retrieved chunks, each scene records which chunks it was written from, and the
// player turns those into the same citations chat gives — clickable through to the page.
//
// **The gate runs before the worker is contacted, and that ordering is the point.** The most
// expensive action in this product must have the cheapest possible failure. A topic the corpus
// cannot support ends here, at the cost of embedding one short string, rather than after four
// minutes of Manim have produced a confident film about material that does not exist. Putting the
// gate inside the pipeline instead — refusing at composition time, say — would be the same policy
// with none of the benefit.
//
// **Two model calls, both bounded before rendering starts.** One for the script, one for the
// visual plan. The tempting third — retrieve again per scene, so each scene gets its own context —
// is Phase 22.1's problem and it does not belong in front of a renderer: it turns a fixed cost
// into a cost that scales with scene count, in a feature whose whole risk is unbounded cost.
@Service
@RequiredArgsConstructor
public class VideoPlanner {

    // Web starter 4.1 publishes no ObjectMapper bean; QuizService makes its own for the same
    // reason, and with the same tolerance for a model that adds a field nobody asked for.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RetrievalService retrievalService;
    private final ConfidenceGate confidenceGate;
    private final ChatClient chatClient;
    private final VideoProperties properties;

    // Empty when the corpus cannot support the topic — the gate's decision, unappealed, and the
    // same decision chat would have made about the same question.
    //
    // Read-only and short. The two model calls are the slow part and there is nothing to write
    // here; the runner persists what comes back.
    @Transactional(readOnly = true)
    public Optional<VideoPlan> plan(UUID actorId, UUID courseId, String topic) {
        RetrievalResult retrieval = retrievalService.search(
                actorId, courseId, topic, properties.retrievalK());
        if (confidenceGate.shouldRefuse(retrieval)) {
            return Optional.empty();
        }

        List<RetrievedChunk> chunks = retrieval.chunks();
        // The language of the material, not of the request. A student typing an English topic
        // against a Bangla textbook should get the textbook's language read aloud, because that
        // is the vocabulary the citations are in — see Phase 19.3 for the same rule in chat.
        Language language = dominantLanguage(chunks);

        ScriptDraft script = callScript(topic, chunks, language);
        if (script == null || script.scenes() == null || script.scenes().isEmpty()) {
            throw new VideoWorkerException("The model returned a script with no scenes.");
        }
        List<ScriptScene> scenes = script.scenes().stream()
                .filter(scene -> scene != null && scene.narration() != null && !scene.narration().isBlank())
                .limit(properties.maxScenes())
                .toList();
        if (scenes.isEmpty()) {
            throw new VideoWorkerException("The model returned a script with no narration.");
        }

        VisualDraft visuals = callScenePlan(topic, scenes, language);
        return Optional.of(assemble(topic, language, scenes, visuals, chunks));
    }

    // ── the two calls ───────────────────────────────────────────────────────────────────────

    private ScriptDraft callScript(String topic, List<RetrievedChunk> chunks, Language language) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(scriptPrompt(language)),
                LlmMessage.user("Topic: " + topic + "\n\nCourse material:\n\n" + material(chunks)));
        String json;
        try (var ignored = AiUsageContext.of(AiOperation.VIDEO_SCRIPT)) {
            json = chatClient.completeJson(messages);
        }
        return parse(json, ScriptDraft.class, "script");
    }

    private VisualDraft callScenePlan(String topic, List<ScriptScene> scenes, Language language) {
        StringBuilder outline = new StringBuilder("Topic: ").append(topic).append("\n\n");
        for (int i = 0; i < scenes.size(); i++) {
            outline.append(i + 1).append(". ").append(scenes.get(i).title()).append('\n')
                    .append(scenes.get(i).narration()).append("\n\n");
        }
        List<LlmMessage> messages = List.of(
                LlmMessage.system(visualPrompt(scenes.size(), language)),
                LlmMessage.user(outline.toString()));
        String json;
        try (var ignored = AiUsageContext.of(AiOperation.VIDEO_SCENE_PLAN)) {
            json = chatClient.completeJson(messages);
        }
        return parse(json, VisualDraft.class, "scene plan");
    }

    private <T> T parse(String json, Class<T> type, String what) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new VideoWorkerException("The model returned a malformed " + what + ".", e);
        }
    }

    // ── assembly ────────────────────────────────────────────────────────────────────────────

    // Merges the two drafts and resolves each scene's source numbers back to chunk ids.
    //
    // **A scene with no resolvable source keeps its narration and loses its citations**, rather
    // than being dropped. The model citing nothing on the closing scene is normal — a summary is
    // not grounded in one passage — and deleting that scene would leave a video that stops
    // mid-thought to enforce a rule about a slide nobody was going to click.
    private VideoPlan assemble(String topic, Language language, List<ScriptScene> scenes,
                               VisualDraft visuals, List<RetrievedChunk> chunks) {
        int animatedBudget = properties.maxAnimatedScenes();
        List<PlannedScene> planned = new ArrayList<>(scenes.size());
        for (int i = 0; i < scenes.size(); i++) {
            ScriptScene scene = scenes.get(i);
            VisualScene visual = visuals == null ? null : visuals.forIndex(i + 1);

            boolean wantsAnimation = visual != null && "ANIMATED".equalsIgnoreCase(visual.visual());
            boolean animate = wantsAnimation && animatedBudget > 0;
            if (animate) {
                animatedBudget--;
            }

            planned.add(new PlannedScene(
                    i + 1,
                    title(scene, i),
                    scene.narration().strip(),
                    animate,
                    visual == null ? null : visual.description(),
                    bullets(visual, scene),
                    resolveSources(scene.sources(), chunks)));
        }
        return new VideoPlan(topic, language, planned, chunks);
    }

    // Bullets are produced for every scene, animated or not, and that is the fallback story in one
    // line: when an animation loses, there is already something to draw. Building them only for
    // planned slides would mean the fallback slide had to be invented from the narration at the
    // moment of failure, which is the point in the job when there is least to work with.
    private static List<String> bullets(VisualScene visual, ScriptScene scene) {
        if (visual != null && visual.bullets() != null && !visual.bullets().isEmpty()) {
            return visual.bullets().stream()
                    .filter(line -> line != null && !line.isBlank())
                    .map(String::strip)
                    .limit(5)
                    .toList();
        }
        return List.of(scene.title() == null ? "" : scene.title().strip());
    }

    private static String title(ScriptScene scene, int index) {
        return scene.title() == null || scene.title().isBlank()
                ? "Scene " + (index + 1)
                : scene.title().strip();
    }

    // The model cites by the [n] markers it was shown, so the mapping back is positional. Numbers
    // outside the range are dropped silently: a model inventing [9] against six sources is a
    // hallucinated citation, and the correct amount of it to render is none.
    private static List<UUID> resolveSources(List<Integer> sources, List<RetrievedChunk> chunks) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (Integer source : sources) {
            if (source != null && source >= 1 && source <= chunks.size()) {
                ids.add(chunks.get(source - 1).chunkId());
            }
        }
        return List.copyOf(ids);
    }

    // Majority script among the retrieved chunks. A tie or an empty list is English, which is the
    // fallback everywhere else in Phase 19 and for the same reason: it is what a document is when
    // nothing says otherwise, not a peer of the other value.
    private Language dominantLanguage(List<RetrievedChunk> chunks) {
        int bangla = 0;
        for (RetrievedChunk chunk : chunks) {
            if (chunk.content() != null && hasBengali(chunk.content())) {
                bangla++;
            }
        }
        return bangla * 2 > chunks.size() ? Language.BANGLA : Language.ENGLISH;
    }

    private static boolean hasBengali(String text) {
        int limit = Math.min(text.length(), 400);
        for (int i = 0; i < limit; i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.BENGALI) {
                return true;
            }
        }
        return false;
    }

    private static String material(List<RetrievedChunk> chunks) {
        StringBuilder material = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            material.append('[').append(i + 1).append("] ").append(chunk.filename());
            if (chunk.pageNumber() != null) {
                material.append(", p.").append(chunk.pageNumber());
            }
            material.append('\n').append(chunk.content().strip()).append("\n\n");
        }
        return material.toString();
    }

    // ── prompts ─────────────────────────────────────────────────────────────────────────────

    private String scriptPrompt(Language language) {
        return """
                You are StudyLoop's video script writer. Using ONLY the course material provided, \
                write the narration for a short explanatory video about the topic.

                Rules:
                - %d scenes at most. Aim for about %d seconds of speech in total, which is roughly \
                %d words spread across the scenes.
                - Write the narration in %s. Every word of it is going to be read aloud, so write \
                sentences a person can say: no bullet points, no markdown, no "[1]" markers, no \
                symbols that are read differently than they are written.
                - Each scene states which numbered sources it was written from, in "sources".
                - Do not add facts that are not in the material. If the material does not cover \
                part of the topic, leave that part out rather than filling it in.
                - The last scene is a short recap. It may have no sources.

                Reply with one JSON object and nothing else:
                {"scenes":[{"title":"short scene title","narration":"what the voice says",\
                "sources":[1,2]}]}
                """.formatted(
                properties.maxScenes(),
                properties.targetSeconds(),
                properties.targetSeconds() * 150 / 60,
                language.promptName());
    }

    private String visualPrompt(int sceneCount, Language language) {
        return """
                You are StudyLoop's storyboard planner. For each scene of a narrated video, decide \
                what the viewer should see.

                There are %d scenes. For each one, reply with:
                - "visual": "ANIMATED" when the scene explains something that moves, builds up, or \
                has parts that relate to each other — a graph being traversed, a formula being \
                derived, a data structure changing. "STATIC" when the scene is a definition, a \
                list, a comparison or a recap, where an animation would be decoration.
                - "description": one sentence describing what the animation should show. Concrete \
                and drawable: name the objects, say what moves, say in what order. Only for \
                ANIMATED scenes.
                - "bullets": two to four very short lines for the viewer to read — under eight \
                words each, written in %s. Required for EVERY scene, including animated ones: they \
                are what gets shown if the animation cannot be produced.

                At most %d scenes may be ANIMATED. Choose the ones where movement actually explains \
                something.

                Reply with one JSON object and nothing else:
                {"scenes":[{"index":1,"visual":"ANIMATED","description":"...","bullets":["...","..."]}]}
                """.formatted(sceneCount, language.promptName(), properties.maxAnimatedScenes());
    }

    // ── shapes ──────────────────────────────────────────────────────────────────────────────

    // The finished plan. `chunks` rides along because the runner needs them for two things the
    // scenes alone cannot supply: the closing source slide, and the context the Manim generator is
    // given so an animation can be about the material rather than about the narration.
    public record VideoPlan(String topic, Language language, List<PlannedScene> scenes,
                            List<RetrievedChunk> chunks) { }

    public record PlannedScene(int index, String title, String narration, boolean animate,
                               String visualDescription, List<String> bullets, List<UUID> chunkIds) { }

    private record ScriptDraft(List<ScriptScene> scenes) { }

    private record ScriptScene(String title, String narration, List<Integer> sources) { }

    private record VisualDraft(List<VisualScene> scenes) {

        VisualScene forIndex(int index) {
            if (scenes == null) {
                return null;
            }
            for (VisualScene scene : scenes) {
                if (scene != null && scene.index() != null && scene.index() == index) {
                    return scene;
                }
            }
            return null;
        }
    }

    private record VisualScene(Integer index, String visual, String description, List<String> bullets) { }
}
