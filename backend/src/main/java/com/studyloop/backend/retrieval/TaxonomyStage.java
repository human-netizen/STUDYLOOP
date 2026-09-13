package com.studyloop.backend.retrieval;

import com.studyloop.backend.config.RetrievalProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Phase 23.2 — narrowing a search to the material the question named, when the course has any.
//
// **This is a pre-filter and the plan said post-fusion, and the box was written on a premise that
// has since been removed.** 23.2 was written in August, when a `where` clause beside a pgvector
// HNSW scan meant the filter was applied to whatever forty rows the index happened to choose — so
// filtering inside the scan really did cost recall, and filtering after fusion was the safe
// answer. Phase 17 found that same behaviour as a bug (`rows=40` is `hnsw.ef_search`) and fixed it
// for every query in this codebase by setting `hnsw.iterative_scan = relaxed_order` on the pool:
// the index now keeps scanning until it has enough neighbours that pass the predicate. Phase 28.2
// then shipped a document-id predicate inside all six branches on the strength of that.
//
// With the premise gone the two are not equivalent and the pre-filter is the better one, because
// of where `limit 20` lands. Filtered inside the SQL, each ranked list is twenty candidates *from
// week 3*. Filtered after fusion, it is whatever survives of twenty candidates drawn from the
// whole course — so on a corpus where week 3 is a fourteenth of the material, a post-fusion filter
// would routinely hand the reranker two passages and call it a narrowed search. The deviation is
// recorded in DEVIATIONS.md.
//
// **What it reuses is 28.2's scope, not a second mechanism.** The filter resolves to document ids
// and those go through `DocumentScope`, which every branch — including the two conditional stages
// that are off — already threads. So this phase adds no SQL shape, no new predicate, and nothing
// that has to be remembered in six places.
@Component
@RequiredArgsConstructor
public class TaxonomyStage {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyStage.class);

    private final RetrievalProperties properties;
    private final TaxonomyFilterExtractor extractor;
    private final TaxonomyScopeRepository scopeRepository;

    public boolean enabled() {
        return properties.stages().taxonomy();
    }

    // Whether this question is one this stage could narrow: the stage is on and the question
    // contains a taxonomy word. Deliberately *not* whether it will be narrowed, which needs the
    // database.
    //
    // It exists for the semantic cache, which has to decide before retrieval runs. A cache entry
    // is keyed on the course and the question's embedding with no room for a narrowing, so a
    // narrowed turn must neither read nor write one - the same rule 28.2 wrote for a reader-chosen
    // scope. Answering the cheap half of the question here keeps the cache's rule one line at its
    // own call site instead of a flag threaded back out of retrieval.
    public boolean mayNarrow(String query) {
        return enabled() && !extractor.extract(query).filter().isEmpty();
    }

    // What this question should be searched over, and what to tell the reader about it.
    //
    // **Three ways out before the database is touched**, which is what keeps an ordinary question
    // at exactly the cost it had before this phase: the stage is off; the question contains no
    // taxonomy word at all; or the reader already chose a scope. Only the second is a measurement,
    // and it is a regex — the resolve query runs only once something has matched.
    //
    // The order of the last two is deliberate and the comment below says why: the scope check
    // skips the *narrowing*, not the extraction, because the residual query is owed either way.
    public Result narrow(UUID courseId, UUID actorId, String query, DocumentScope chosen) {
        if (!enabled()) {
            return Result.unchanged(chosen, query);
        }
        TaxonomyFilterExtractor.Extraction extraction = extractor.extract(query);
        TaxonomyFilter filter = extraction.filter();
        if (filter.isEmpty()) {
            return Result.unchanged(chosen, query);
        }

        // **The extraction runs before the scope check, and the residual query survives every
        // branch below.** A routing phrase is a routing phrase whether or not this course can
        // honour it: "week 9" is not in any chunk of any corpus, so with `lexical-or` off it
        // AND-joins the sparse query into emptiness regardless of what the narrowing decided. The
        // narrowing and the rewrite are two separate answers to two separate questions — *which
        // documents* and *which words* — and only the first of them depends on the corpus.
        //
        // The trade, stated: a course whose material genuinely discusses "weekly" data loses that
        // word from the query. That is a smaller loss than the whole sparse half, and it needs the
        // stage on and the word in a question that also parses as a filter.
        String searchQuery = extraction.searchQuery();

        // **An explicit scope wins and is never intersected with an inferred one.** The reader
        // picked documents; silently removing some of them because the question also said "lab"
        // is the one outcome here that is worse than not filtering — they would be looking at the
        // chips they chose while being answered from a subset of them.
        if (!chosen.isWholeCourse()) {
            return Result.unchanged(chosen, searchQuery);
        }

        List<UUID> ids = scopeRepository.resolve(courseId, actorId, filter);
        if (ids.isEmpty()) {
            // **Not evidenced, so not a filter.** "Week 3" in a course whose materials carry no
            // week numbers is three words in a sentence, and narrowing to nothing would turn a
            // question the corpus can answer into a refusal. Failing open is the same choice the
            // rerank stage makes for the same reason: a stage that cannot do its job must leave
            // the pipeline as it found it.
            log.debug("Taxonomy filter {} matched no documents in course {}; searching all of it",
                    filter, courseId);
            return Result.unchanged(chosen, searchQuery);
        }
        log.debug("Taxonomy filter {} narrowed course {} to {} documents",
                filter, courseId, ids.size());
        return new Result(DocumentScope.of(ids), filter, searchQuery);
    }

    // The scope to search, the filter that produced it — null when none was applied, which is what
    // the client renders as "no narrowing" rather than as an empty badge — and the query to search
    // with, which differs from the question only when the narrowing actually happened.
    public record Result(DocumentScope scope, TaxonomyFilter applied, String searchQuery) {

        static Result unchanged(DocumentScope scope, String query) {
            return new Result(scope, null, query);
        }
    }
}
