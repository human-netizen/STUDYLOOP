package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.chat.ChatClient;
import com.studyloop.backend.chat.LlmMessage;
import com.studyloop.backend.usage.AiOperation;
import com.studyloop.backend.usage.AiUsageContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Phase 18.2 — the one provider call on the retrieval path, and everything that keeps it from
// costing more than it is worth.
//
// It asks for three things at once because they are three readings of one sentence and the round
// trip is the expense: two rewordings, one hypothetical answer, one intent label.
//
// **The hypothetical answer is the interesting output and the reason for the whole technique.** A
// question and the passage that answers it are written in different registers — "why does randomly
// choosing which elements to promote keep a skiplist efficient?" shares almost no vocabulary with
// the paragraph that proves it — and an embedding of the question therefore sits nearer other
// questions than it does to its own answer. HyDE closes that gap by embedding a *fake answer*:
// wrong in its particulars, right in its register, and much nearer the real passage than the
// question was. It does not need to be true. It needs to be shaped like the thing being looked for.
//
// The rewrites go to the two *lexical* retrievers and never to the embedder, which is a division of
// labour rather than an economy. A reworded question is a different bag of words, which is exactly
// what a term index responds to and roughly what an embedder does not; a pseudo-document is a
// different register, which is the opposite. So the sparse side gets the rewrites, the dense side
// gets the hypothetical, and there is one embedding call rather than three.
//
// **Nothing here is allowed to break a chat turn**, the same rule the semantic cache runs under. A
// provider outage, a rate limit, a model that answered in prose instead of JSON, a model that
// returned an intent this enum has never heard of: all of them come back as `empty()`, and the
// pipeline is the one Phase 17 shipped. An expansion stage that could 502 a question the corpus
// could answer would be a strictly worse system than not having it.
@Component
@RequiredArgsConstructor
public class QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);

    // Boot 4.1's modular web starter publishes no ObjectMapper bean (see BUGS.md), so this class
    // keeps its own, as SemanticCacheService and the summary reader do.
    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Two rewrites, not five. Each one is another lexical query against the same course, and the
    // fourth rewording of a question is a paraphrase of the third rather than a new way in.
    private static final int MAX_REWRITES = 2;

    private static final String SYSTEM_PROMPT = """
            You rewrite a student's question so a search engine over their course materials can \
            find the passage that answers it. Reply with a single JSON object and nothing else:

            {
              "intent": "lookup" | "explain" | "compare",
              "rewrites": ["<the same question in different words>", "<and again>"],
              "hypothetical": "<a short passage, 2-3 sentences, written the way a textbook would \
            write the answer>"
            }

            - "intent" is "lookup" for a question with one definite answer (a bound, a count, a \
            name), "compare" for a question that holds two things against each other, and \
            "explain" for anything else.
            - Each rewrite keeps the meaning and changes the wording. Use the vocabulary a \
            textbook would use, not the vocabulary of the question.
            - "hypothetical" is written as if it were an extract from the course material. Do not \
            hedge, do not say you are unsure, and do not mention the question. If you do not know \
            the real answer, write the passage you would expect to find. It is used to search \
            with and is never shown to anyone.
            """;

    private final ChatClient chatClient;

    public boolean isConfigured() {
        return chatClient.isConfigured();
    }

    // One call, everything or nothing. Never throws.
    public QueryExpansion expand(String question) {
        if (!chatClient.isConfigured()) {
            return QueryExpansion.empty();
        }
        String json;
        try (var ignored = AiUsageContext.of(AiOperation.QUERY_EXPANSION)) {
            json = chatClient.completeJson(List.of(
                    LlmMessage.system(SYSTEM_PROMPT), LlmMessage.user(question)));
        } catch (RuntimeException e) {
            log.warn("Query expansion failed, retrieving with the question as typed: {}", e.getMessage());
            return QueryExpansion.empty();
        }
        return parse(json, question);
    }

    private QueryExpansion parse(String json, String question) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return new QueryExpansion(
                    rewrites(root.path("rewrites"), question),
                    text(root.path("hypothetical")),
                    intent(root.path("intent")));
        } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException e) {
            log.warn("Query expansion returned unusable JSON: {}", e.getMessage());
            return QueryExpansion.empty();
        }
    }

    // Rewrites that are blank, duplicated, or identical to the question itself are dropped rather
    // than searched: each one is a database round trip, and a "rewrite" equal to the original is a
    // second copy of a ranking the pipeline already has, which in RRF is a vote counted twice.
    private static List<String> rewrites(JsonNode node, String question) {
        if (!node.isArray()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        seen.add(question.toLowerCase(Locale.ROOT).strip());
        List<String> rewrites = new ArrayList<>(MAX_REWRITES);
        for (JsonNode entry : node) {
            String rewrite = text(entry);
            if (rewrite != null && seen.add(rewrite.toLowerCase(Locale.ROOT).strip())) {
                rewrites.add(rewrite);
                if (rewrites.size() == MAX_REWRITES) {
                    break;
                }
            }
        }
        return rewrites;
    }

    private static String text(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    // An unrecognised label is null rather than a default, and the difference matters: null means
    // "the model said nothing usable", which leaves the rule-based classification standing, while
    // defaulting to EXPLAIN here would let a garbled response silently overrule it.
    private static QueryIntent intent(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return QueryIntent.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
