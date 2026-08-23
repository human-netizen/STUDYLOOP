package com.studyloop.backend.document;

import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.config.ChunkingProperties;
import com.studyloop.backend.config.ChunkingProperties.SyntheticQueries;
import com.studyloop.backend.config.RetrievalProperties;
import com.studyloop.backend.config.RetrievalProperties.Stages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Phase 14 — doc2query, and the four properties that make it safe to turn on.
//
// The measurable claim (Recall@6 with and without the block) belongs to the eval harness and needs
// a real corpus and a real provider. What is checkable here, cheaply and in CI, is everything the
// eval cannot see: that the generated text goes only where it is allowed to go, that it is bounded,
// that a partial or inventive reply degrades rather than corrupts, and that a provider failure is
// loud instead of leaving a corpus that is quietly half-indexed.
class SyntheticQueryTest {

    private final TokenCounter tokens = new TokenCounter();
    private final FakeChatClient chat = new FakeChatClient();

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private SyntheticQueryGenerator generator(boolean stageOn) {
        return generator(stageOn, new SyntheticQueries(6, 8, 0.20, false));
    }

    private SyntheticQueryGenerator generator(boolean stageOn, SyntheticQueries settings) {
        RetrievalProperties retrieval = new RetrievalProperties(
                new Stages(false, false, false, false, false, false, stageOn), null, null, null);
        ChunkingProperties chunking =
                new ChunkingProperties(500, 120, true, 400, true, true, 1_200, settings);
        return new SyntheticQueryGenerator(retrieval, chunking, chat, tokens);
    }

    // A section big enough that a 20% share leaves room for several questions.
    private TextChunk section(int index, String content) {
        return new TextChunk(index, 1, 1, "Skiplists > 4.4 Analysis", content,
                "04 skiplists\nSkiplists > 4.4 Analysis\n" + content, tokens.count(content), false);
    }

    private TextChunk overflowPiece(int index, String content) {
        return new TextChunk(index, 1, 1, "Skiplists > 4.4 Analysis", content,
                "04 skiplists\nSkiplists > 4.4 Analysis\n" + content, tokens.count(content), true);
    }

    private static String prose(int sentences) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < sentences; i++) {
            text.append("Sentence number ").append(i)
                    .append(" explains a property of the structure under discussion here. ");
        }
        return text.toString().strip();
    }

    private static String reply(String... sections) {
        return "{\"sections\":[" + String.join(",", sections) + "]}";
    }

    private static String forSection(int id, String... questions) {
        List<String> quoted = new ArrayList<>();
        for (String question : questions) {
            quoted.add('"' + question + '"');
        }
        return "{\"id\":%d,\"questions\":[%s]}".formatted(id, String.join(",", quoted));
    }

    // ── the switch ──────────────────────────────────────────────────────────────────────────

    @Test
    void theStageIsOffByDefaultAndCostsNothingWhenItIs() {
        List<TextChunk> chunks = List.of(section(0, prose(20)));

        assertThat(generator(false).augment(chunks)).isSameAs(chunks);
        assertThat(chat.calls).isZero();
    }

    @Test
    void anUnconfiguredProviderLeavesIngestionWorkingRatherThanFailingEveryUpload() {
        chat.configured = false;
        List<TextChunk> chunks = List.of(section(0, prose(20)));

        // The flag says "index synthetic queries" and the deployment has no chat key. Refusing the
        // upload would take documents away from an installation that never opted into this.
        assertThat(generator(true).augment(chunks)).isSameAs(chunks);
        assertThat(chat.calls).isZero();
    }

    // ── where the text may go, and where it may not ─────────────────────────────────────────

    @Test
    void theQuestionsReachTheIndexedTextAndNeverTheDisplayedText() {
        chat.nextJson = reply(forSection(0,
                "why is search fast on average", "what is the expected search time"));
        TextChunk chunk = generator(true).augment(List.of(section(0, prose(30)))).get(0);

        // This is the safety property the whole phase rests on. `content` is what SectionExpander
        // reads, what the prompt is built from, what the reranker scores and what a citation opens
        // — so a model-invented question in there would reach the answer model as course material.
        assertThat(chunk.embedText())
                .contains(SyntheticQueryGenerator.MARKER)
                .contains("- why is search fast on average")
                .contains("- what is the expected search time");
        assertThat(chunk.content())
                .doesNotContain(SyntheticQueryGenerator.MARKER)
                .doesNotContain("why is search fast on average");
    }

    @Test
    void theBlockLandsAfterThePassageAndTheContextHeaderRatherThanReplacingEither() {
        chat.nextJson = reply(forSection(0, "why is search fast on average"));
        TextChunk chunk = generator(true).augment(List.of(section(0, prose(30)))).get(0);

        assertThat(chunk.embedText())
                .startsWith("04 skiplists\nSkiplists > 4.4 Analysis\n")
                .contains(chunk.content())
                .endsWith("- why is search fast on average");
    }

    @Test
    void aChunkWithNoContextHeaderStillGetsItsQuestions() {
        // context-header=false leaves embed_text null, which is the SQL's signal to index
        // `content`. Appending to null would either lose the passage or lose the questions.
        String content = prose(30);
        TextChunk plain = new TextChunk(0, 1, 1, null, content, null, tokens.count(content), false);
        chat.nextJson = reply(forSection(0, "why is search fast on average"));

        TextChunk chunk = generator(true).augment(List.of(plain)).get(0);

        assertThat(chunk.embedText())
                .startsWith(content)
                .endsWith("- why is search fast on average");
        assertThat(chunk.content()).isEqualTo(content);
    }

    @Test
    void nothingElseAboutTheChunkMoves() {
        chat.nextJson = reply(forSection(0, "why is search fast on average"));
        TextChunk before = section(0, prose(30));
        List<TextChunk> after = generator(true).augment(List.of(before));

        // Zero new rows, zero new vectors, no change to (document, chunk_index) — the phase adds
        // no unit of retrieval, it lengthens a string that was going to be embedded anyway.
        assertThat(after).hasSize(1);
        TextChunk chunk = after.get(0);
        assertThat(chunk.index()).isEqualTo(before.index());
        assertThat(chunk.pageStart()).isEqualTo(before.pageStart());
        assertThat(chunk.pageEnd()).isEqualTo(before.pageEnd());
        assertThat(chunk.sectionPath()).isEqualTo(before.sectionPath());
        assertThat(chunk.content()).isEqualTo(before.content());
        // token_count still describes the passage, which is the number the ceiling was applied to.
        assertThat(chunk.tokenCount()).isEqualTo(before.tokenCount());
    }

    // ── what is eligible ────────────────────────────────────────────────────────────────────

    @Test
    void aPieceOfASplitSectionIsGeneratedForLikeAnyOther() {
        chat.nextJson = reply(forSection(0, "why is search fast on average"),
                forSection(1, "why is search fast on average"));
        List<TextChunk> chunks = generator(true)
                .augment(List.of(overflowPiece(0, prose(30)), section(1, prose(30))));

        // The plan excluded these. On the fixture corpus that rule reached 13 chunks out of 282,
        // because a 500-token ceiling is smaller than a textbook section — so nearly every chunk
        // is a piece of one, and the rule turned the phase off rather than focusing it.
        assertThat(chunks.get(0).embedText()).contains(SyntheticQueryGenerator.MARKER);
        assertThat(chunks.get(1).embedText()).contains(SyntheticQueryGenerator.MARKER);
    }

    @Test
    void thePlansEligibilityRuleIsStillAvailableAndStillWorks() {
        // Kept switchable because the measurement is the argument: a reader who does not believe
        // the 13-of-282 number should be able to re-run the pipeline it describes.
        chat.nextJson = reply(forSection(0, "why is search fast on average"),
                forSection(1, "why is search fast on average"));
        SyntheticQueryGenerator generator = generator(true, new SyntheticQueries(6, 8, 0.20, true));

        List<TextChunk> chunks =
                generator.augment(List.of(overflowPiece(0, prose(30)), section(1, prose(30))));

        assertThat(chunks.get(0).embedText()).doesNotContain(SyntheticQueryGenerator.MARKER);
        assertThat(chunks.get(1).embedText()).contains(SyntheticQueryGenerator.MARKER);
    }

    @Test
    void aSectionTooSmallForOneQuestionIsSkippedBeforeItCostsACall() {
        // Eight tokens of prose at a 20% share is a budget of one token. Sending it would buy a
        // provider call whose output could not be used.
        List<TextChunk> chunks = List.of(section(0, "A short remark."));

        assertThat(generator(true).augment(chunks)).isSameAs(chunks);
        assertThat(chat.calls).isZero();
    }

    // ── how much is allowed in ──────────────────────────────────────────────────────────────

    @Test
    void theBlockNeverOutweighsItsShareOfTheSection() {
        String content = prose(12);
        chat.nextJson = reply(forSection(0,
                "why is search fast on average",
                "what is the expected search time for a skiplist",
                "how does the analysis bound the number of comparisons",
                "what makes the structure balanced without rotations",
                "why is the height logarithmic in the number of elements",
                "what is the space overhead of the extra lists"));

        TextChunk chunk = generator(true).augment(List.of(section(0, content))).get(0);

        // The block itself, measured whole. Six questions is a modest ask against a long section
        // and half the text of a short one, so `per-section` alone cannot express the dilution
        // limit — twenty short questions would drag the vector toward "question-shaped text".
        String block = chunk.embedText()
                .substring(chunk.embedText().indexOf(SyntheticQueryGenerator.MARKER));
        assertThat(tokens.count(block)).isLessThanOrEqualTo((int) (tokens.count(content) * 0.20));
        // And it did keep some, so the cap is bounding the block rather than deleting it.
        assertThat(block).contains("- why is search fast on average");
        // Not all of them: this section has nowhere near room for six.
        assertThat(block).doesNotContain("- what is the space overhead of the extra lists");
    }

    @Test
    void theCountCapAppliesEvenWhenThereIsRoomForMore() {
        chat.nextJson = reply(forSection(0, "one two three", "four five six", "seven eight nine",
                "ten eleven twelve", "thirteen fourteen"));
        SyntheticQueryGenerator generator = generator(true, new SyntheticQueries(2, 8, 0.9, false));

        TextChunk chunk = generator.augment(List.of(section(0, prose(40)))).get(0);

        assertThat(chunk.embedText()).contains("- one two three").contains("- four five six");
        assertThat(chunk.embedText()).doesNotContain("- seven eight nine");
    }

    // ── a reply that is partial, dirty, or inventive ────────────────────────────────────────

    @Test
    void aSectionTheModelSkippedKeepsItsPlainIndexedText() {
        // A model asked about several sections at once sometimes answers about fewer. That is a
        // section without a bridge, not a corrupt one, and it must not fail the document.
        chat.nextJson = reply(forSection(1, "why is search fast on average"));
        List<TextChunk> chunks = generator(true)
                .augment(List.of(section(0, prose(30)), section(1, prose(30))));

        assertThat(chunks.get(0).embedText()).doesNotContain(SyntheticQueryGenerator.MARKER);
        assertThat(chunks.get(1).embedText()).contains(SyntheticQueryGenerator.MARKER);
    }

    @Test
    void anInventedSectionIdIsDiscardedRatherThanAttachedToSomebodyElse() {
        // Silently the worst failure available here: id 99 was never sent, and a generator that
        // matched by position instead of by id would file its questions under chunk 0.
        chat.nextJson = reply(forSection(99, "a question about a section that was never sent"));
        TextChunk chunk = generator(true).augment(List.of(section(0, prose(30)))).get(0);

        assertThat(chunk.embedText()).doesNotContain("never sent");
        assertThat(chunk.embedText()).doesNotContain(SyntheticQueryGenerator.MARKER);
    }

    @Test
    void listMarkersDuplicatesAndParagraphsAreCleanedOut() {
        chat.nextJson = reply(forSection(0,
                "1. why is search fast on average",
                "- Why Is Search Fast On Average",
                "  * what is the expected search time",
                prose(6)));

        TextChunk chunk = generator(true).augment(List.of(section(0, prose(40)))).get(0);

        String[] lines = chunk.embedText().split("\n");
        List<String> questions = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("- ")) {
                questions.add(line.substring(2));
            }
        }
        // The numbering models add back after being told not to is stripped, the same phrasing in
        // different case is one question, and a paragraph is not a question at all.
        assertThat(questions).containsExactly(
                "why is search fast on average", "what is the expected search time");
    }

    // ── batching ────────────────────────────────────────────────────────────────────────────

    @Test
    void sectionsAreBatchedSoTheCallCountIsNotTheSectionCount() {
        List<TextChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            chunks.add(section(i, prose(30)));
        }
        chat.nextJson = reply();

        generator(true, new SyntheticQueries(6, 8, 0.20, false)).augment(chunks);

        // 20 sections at 8 a call. Per-section would be 20 calls here and 282 on the eval corpus,
        // against a trial key allowing twenty a minute and a thousand a month.
        assertThat(chat.calls).isEqualTo(3);
    }

    @Test
    void eachBatchIsAskedAboutItsOwnSectionsAndNoOthers() {
        List<TextChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            chunks.add(section(i, prose(30)));
        }
        chat.nextJson = reply();

        generator(true, new SyntheticQueries(6, 2, 0.20, false)).augment(chunks);

        assertThat(chat.prompts).hasSize(3);
        assertThat(chat.prompts.get(0)).contains("### section 0").contains("### section 1")
                .doesNotContain("### section 2");
        assertThat(chat.prompts.get(2)).contains("### section 4").doesNotContain("### section 3");
    }

    // ── failure is loud ─────────────────────────────────────────────────────────────────────

    @Test
    void aProviderFailureFailsTheIngestRatherThanHalfIndexingTheDocument() {
        chat.failWith = new IllegalStateException("provider is down");

        assertThatThrownBy(() -> generator(true).augment(List.of(section(0, prose(30)))))
                .isInstanceOf(SyntheticQueryException.class)
                .hasMessageContaining("batch of 1 sections");
    }

    @Test
    void malformedJsonIsAFailureRatherThanASilentlyEmptyBlock() {
        chat.nextJson = "not json at all";

        assertThatThrownBy(() -> generator(true).augment(List.of(section(0, prose(30)))))
                .isInstanceOf(SyntheticQueryException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void aReplyWithNoSectionsIsAnEmptyResultRatherThanAnError() {
        // Well-formed and unhelpful is not the same as broken: the model answered, and it had
        // nothing to say about these sections. The document keeps its plain indexed text.
        chat.nextJson = "{}";
        TextChunk chunk = generator(true).augment(List.of(section(0, prose(30)))).get(0);

        assertThat(chunk.embedText()).doesNotContain(SyntheticQueryGenerator.MARKER);
    }

    // ── the stub ────────────────────────────────────────────────────────────────────────────

    // Records what it was asked as well as how often. The prompt content matters here in a way it
    // does not for the summary tests: the batch boundary is only observable in what each call was
    // sent, and a batching bug that sent every section every time would otherwise pass.
    private static final class FakeChatClient implements ChatClient {

        private volatile boolean configured = true;
        private volatile String nextJson = "{\"sections\":[]}";
        private volatile RuntimeException failWith = null;
        private int calls = 0;
        private final List<String> prompts = new ArrayList<>();

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String complete(List<LlmMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String completeJson(List<LlmMessage> messages) {
            calls++;
            prompts.add(messages.get(messages.size() - 1).content());
            if (failWith != null) {
                throw failWith;
            }
            return nextJson;
        }

        @Override
        public String streamComplete(List<LlmMessage> messages, Consumer<String> onDelta) {
            throw new UnsupportedOperationException();
        }
    }
}
