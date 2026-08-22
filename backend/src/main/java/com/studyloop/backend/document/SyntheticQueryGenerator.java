package com.studyloop.backend.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.config.ChunkingProperties;
import com.studyloop.backend.config.ChunkingProperties.SyntheticQueries;
import com.studyloop.backend.config.RetrievalProperties;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Phase 14.1 — doc2query. Questions a student might ask, written into the text that gets indexed.
//
// **Measured, and it did not help — this ships off by default.** Full coverage (272 of 282 chunks)
// against the Phase 13 corpus: Recall@6 0.939 -> 0.929, nDCG@6 0.828 -> 0.823, MRR unchanged,
// refusals 6/8 unchanged, on the same sixty questions. One synthesis question out of sixty changed
// hands and 59 retrieved identically. The reason is not that the mechanism is broken: it is that
// doc2query closes a *vocabulary* gap and this corpus has none left to close — factual and
// conceptual recall are both 1.000, and what remains is synthesis (needs two chapters in six slots)
// and figures (needs a vision model). Point it at a corpus whose readers and authors really do use
// different words — lecture slides against exam questions — and re-measure before trusting it.
// `RETRIEVAL_SYNTHETIC_QUERIES=true` plus a re-ingest is the whole switch.
//
// **The gap this closes.** A textbook says "the amortized cost of the operation is O(1)"; a student
// types "why is it fast on average". Neither half of hybrid retrieval bridges that on its own — the
// lexical index shares no terms with the question, and a bi-encoder is comparing two vectors that
// were computed without ever seeing each other. HyDE (18.2) attacks the same gap from the other
// side by moving the *query* toward the document at query time. This moves the document toward the
// query at ingest time, which costs nothing per question and — unlike HyDE — helps the lexical
// half, because generated questions are keyword-dense paraphrases and a GIN-indexed tsvector eats
// exactly that.
//
// **Where the text lands, and where it must never land.** `embedText` only, appended after the
// context header and the passage. `content` is untouched, and `content` is what SectionExpander
// reads, what the prompt is built from, what the reranker scores and what a citation opens. A
// model-invented question must not reach the answer model as if it were course material. The one
// place the two rejoin is `content_tsv`, which V18 already generates from `coalesce(embed_text,
// content)` — that column was rebuilt for this phase.
//
// **The plan said tier 1 and tier 2 only, and the corpus said otherwise.** The rule was written
// before adaptive chunking existed, on the reasoning that a tier-3 piece stops where a counter ran
// out, so asking what it answers gets questions about wherever the counter landed. Measured on the
// fixture corpus it excluded all but **13 of 282 chunks** — a 500-token ceiling is smaller than a
// textbook section, so nearly every chunk in the book is a piece of a section rather than a whole
// one, and the rule turned the phase off rather than focusing it.
//
// Two things make the exclusion unnecessary anyway. A tier-3 piece is cut on *paragraph* boundaries
// and carries its full heading path, so it is a coherent run of prose under a known topic, not the
// mid-sentence fragment the sliding window used to produce. And the risk the rule was guarding
// against — a generated question the passage does not really answer, retrieving well — is already
// carried by the reranker, which scores `content` and never sees the block.
//
// The provenance is still recorded on TextChunk.overflow and the rule is still available as
// `whole-sections-only`, because the measurement is the argument and both sides of it should be
// re-runnable.
//
// **Batched, against the plan's wording.** The plan said one cheap generation call per section.
// Per token that is the right price and batching does not change it — the same section text is
// sent either way. What it changes is the *call count*, and the call count is what the provider
// limits: 282 sections in the eval corpus is 282 chat calls, against a trial key that allows twenty
// a minute and a thousand a month. One re-ingest would spend a quarter of the month and twelve
// minutes of wall clock. Eight sections a call made it forty on the measured run. The cost is that a model
// asked about eight sections at once sometimes answers about seven, which is why a section missing
// from the reply keeps its plain embed text rather than failing the batch.
@Component
@RequiredArgsConstructor
public class SyntheticQueryGenerator {

    private static final Logger log = LoggerFactory.getLogger(SyntheticQueryGenerator.class);

    // The block's own heading, and the marker that makes a corpus auditable. Counting the rows whose
    // embed_text contains it answers "is this corpus fully generated, half generated, or not
    // generated at all" with no column to store it in — which is the question an eval report has to
    // answer before it quotes a number.
    public static final String MARKER = "Questions this section answers:";

    // Shortest thing that could plausibly be a question, in tokens. Under this the share cap has
    // left no room for even one, so the section is skipped before it costs a provider call rather
    // than after: an 80-token section at a 20% share has a budget of sixteen.
    private static final int MIN_QUESTION_TOKENS = 8;
    // A "question" longer than this is a paragraph the model wrote instead. Dropped rather than
    // truncated — half a question is not a question.
    private static final int MAX_QUESTION_TOKENS = 40;

    // The same bounded wait the embedding client uses, for the same reason: ingestion is
    // asynchronous with a status machine in front of it and nobody waiting on the thread, so a
    // minute of sleeping is cheaper than a failed document. Linear, because the window being waited
    // out is a fixed minute — doubling would spend the later retries asleep long after it reset.
    //
    // The retry is here rather than inside CohereChatClient on purpose. completeJson is also how a
    // student's quiz and a document summary are generated, and both of those have somebody watching
    // a spinner. This is the one caller that can afford to wait, so it is the one that waits — the
    // same line Phase 12.1 drew when it refused to retry inside the rerank client.
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final Duration RETRY_WAIT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RetrievalProperties retrievalProperties;
    private final ChunkingProperties chunkingProperties;
    private final ChatClient chatClient;
    private final TokenCounter tokenCounter;

    // Off unless the stage is on *and* a provider is configured. The second half matters: the flag
    // ships false, and a deployment with no chat key has to keep ingesting documents rather than
    // fail every upload on a stage it never asked for.
    public boolean isEnabled() {
        return retrievalProperties.stages().syntheticQueries() && chatClient.isConfigured();
    }

    // Returns the chunks with a synthetic block appended to what each one is indexed as. Order,
    // indices, count and `content` are all unchanged — this phase adds no rows and no vectors, it
    // lengthens a string that was going to be embedded anyway.
    public List<TextChunk> augment(List<TextChunk> chunks) {
        if (!isEnabled()) {
            return chunks;
        }
        SyntheticQueries settings = chunkingProperties.syntheticQueries();
        List<TextChunk> eligible = chunks.stream()
                .filter(chunk -> budgetFor(chunk, settings) >= MIN_QUESTION_TOKENS)
                .toList();
        if (eligible.isEmpty()) {
            return chunks;
        }

        Map<Integer, List<String>> generated = new HashMap<>();
        for (List<TextChunk> batch : batches(eligible, settings.batchSize())) {
            generated.putAll(callModel(batch, settings.perSection()));
        }

        List<TextChunk> augmented = new ArrayList<>(chunks.size());
        int written = 0;
        for (TextChunk chunk : chunks) {
            String block = blockFor(chunk, generated.get(chunk.index()), settings);
            if (block == null) {
                augmented.add(chunk);
                continue;
            }
            augmented.add(chunk.withIndexedSuffix(block));
            written++;
        }
        log.info("Synthetic queries: {} of {} eligible sections augmented ({} chunks total)",
                written, eligible.size(), chunks.size());
        return augmented;
    }

    // A chunk is worth generating for when the share cap leaves room for at least one question,
    // and — under the plan's original rule, now off by default — when the document rather than the
    // ceiling chose its boundaries. See the class comment for why that rule was measured out.
    private int budgetFor(TextChunk chunk, SyntheticQueries settings) {
        if (settings.wholeSectionsOnly() && chunk.overflow()) {
            return 0;
        }
        return (int) (chunk.tokenCount() * settings.share());
    }

    private static List<List<TextChunk>> batches(List<TextChunk> eligible, int size) {
        List<List<TextChunk>> batches = new ArrayList<>();
        for (int start = 0; start < eligible.size(); start += size) {
            batches.add(eligible.subList(start, Math.min(start + size, eligible.size())));
        }
        return batches;
    }

    // The block as it will be appended, or null when there is nothing usable to append. Trimming
    // happens here rather than in the prompt because a model asked for "six short questions"
    // answers at whatever length it likes, and the cap that matters is on tokens, not on count.
    private String blockFor(TextChunk chunk, List<String> questions, SyntheticQueries settings) {
        if (questions == null || questions.isEmpty()) {
            return null;
        }
        int budget = budgetFor(chunk, settings);
        String block = MARKER;
        int kept = 0;
        for (String question : questions) {
            if (kept >= settings.perSection()) {
                break;
            }
            // The whole block re-counted each time, rather than a running sum of the questions.
            // Two reasons, and the first one was a bug here: a running sum charges nothing for
            // the marker line or for the "- " in front of every question, which is three tokens
            // a question the cap never sees. The second is that byte-pair encoding is not
            // additive across a join — count(a) + count(b) is not count(a + b) — so a sum is an
            // estimate of the quantity the ceiling is supposed to bound exactly. Eight questions
            // at most, so this is eight tokenizer calls over a few hundred characters, once per
            // section at ingest.
            String candidate = block + "\n- " + question;
            if (tokenCounter.count(candidate) > budget) {
                break;
            }
            block = candidate;
            kept++;
        }
        return kept == 0 ? null : block;
    }

    // One provider call for a batch of sections, returning chunk index to questions.
    //
    // A batch that fails after its retries throws rather than returning what it has. The corpus
    // would otherwise be a blend — some sections indexed with their questions and some without,
    // under a configuration saying every section has them — and nothing downstream could tell the
    // difference. SyntheticQueryException records why that is worth failing an upload over.
    private Map<Integer, List<String>> callModel(List<TextChunk> batch, int perSection) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt(perSection)),
                LlmMessage.user(renderSections(batch)));

        String json = null;
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            try (var ignored = AiUsageContext.of(AiOperation.SYNTHETIC_QUERIES)) {
                json = chatClient.completeJson(messages);
                break;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt == MAX_RATE_LIMIT_RETRIES || !isRateLimited(e)) {
                    break;
                }
                long wait = RETRY_WAIT.toMillis() * (attempt + 1);
                log.warn("Rate-limited generating synthetic queries; waiting {}s and retrying ({}/{})",
                        wait / 1000, attempt + 1, MAX_RATE_LIMIT_RETRIES);
                sleep(wait);
            }
        }
        if (json == null) {
            throw new SyntheticQueryException(
                    "The synthetic-query provider failed for a batch of " + batch.size() + " sections.",
                    lastFailure);
        }

        GeneratedBatch parsed;
        try {
            parsed = objectMapper.readValue(json, GeneratedBatch.class);
        } catch (JsonProcessingException e) {
            throw new SyntheticQueryException("The model returned malformed synthetic-query JSON.", e);
        }
        return usable(parsed, batch);
    }

    // Keeps only what is well-formed and about a section that was actually sent. The id check is
    // not paranoia: a model given eight numbered sections occasionally invents a ninth, and an
    // invented id would attach one section's questions to a different section's chunk index.
    private Map<Integer, List<String>> usable(GeneratedBatch parsed, List<TextChunk> batch) {
        Set<Integer> sent = new LinkedHashSet<>(batch.stream().map(TextChunk::index).toList());
        Map<Integer, List<String>> result = new HashMap<>();
        if (parsed == null || parsed.sections() == null) {
            return result;
        }
        for (GeneratedSection section : parsed.sections()) {
            if (section == null || section.id() == null || !sent.contains(section.id())) {
                continue;
            }
            List<String> questions = cleaned(section.questions());
            if (!questions.isEmpty()) {
                result.put(section.id(), questions);
            }
        }
        return result;
    }

    // Strips the list markers models add back after being told not to, drops anything that is a
    // paragraph pretending to be a question, and de-duplicates case-insensitively — the same
    // phrasing twice in one block is one question indexed twice, which is the dilution the share
    // cap exists to bound.
    private List<String> cleaned(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> questions = new ArrayList<>();
        for (String candidate : raw) {
            if (candidate == null) {
                continue;
            }
            String question = candidate.strip().replaceFirst("^[-*\\d.)\\s]+", "").strip();
            if (question.isEmpty() || tokenCounter.count(question) > MAX_QUESTION_TOKENS) {
                continue;
            }
            if (seen.add(question.toLowerCase(Locale.ROOT))) {
                questions.add(question);
            }
        }
        return questions;
    }

    // The sections as the model sees them: an id it must echo back, the heading trail for context,
    // and the passage. The id is the chunk index, so nothing has to be mapped back afterwards.
    private static String renderSections(List<TextChunk> batch) {
        StringBuilder out = new StringBuilder();
        for (TextChunk chunk : batch) {
            out.append("### section ").append(chunk.index()).append('\n');
            if (chunk.sectionPath() != null) {
                out.append("heading: ").append(chunk.sectionPath()).append('\n');
            }
            out.append(chunk.content()).append("\n\n");
        }
        return out.toString();
    }

    private static boolean isRateLimited(RuntimeException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("429") || message.toLowerCase(Locale.ROOT).contains("rate limit"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SyntheticQueryException("Interrupted while waiting out a rate limit", interrupted);
        }
    }

    private static String systemPrompt(int perSection) {
        return """
                You are StudyLoop's search-bridge generator. For each section of course material \
                the user gives you, write the questions a student would type into a search box \
                that this section, specifically, answers.

                Return a SINGLE JSON object, nothing else, with this exact shape:
                {
                  "sections": [
                    { "id": 0, "questions": ["...", "..."] }
                  ]
                }

                Rules:
                - Echo back the id of every section you were given, using the number in its header.
                - At most %d questions per section, and fewer when the section is short.
                - Write how a student writes: plain words, and the term the section introduces only \
                where that term is what they would search for.
                - Every question must be answerable from that section alone. Do not write a \
                question about something the section only mentions in passing.
                - Vary the phrasing. Some literal, some in the words of somebody who does not know \
                the term yet: "why is it fast on average" as well as "what is the amortized cost".
                - One line each. No numbering, no bullets, no commentary.
                """.formatted(perSection);
    }

    // ── model output shapes (parsed from completeJson) ──────────────────────────────────────

    record GeneratedBatch(List<GeneratedSection> sections) { }

    record GeneratedSection(Integer id, List<String> questions) { }
}
