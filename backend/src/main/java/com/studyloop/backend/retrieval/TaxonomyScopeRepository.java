package com.studyloop.backend.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Phase 23.2 — turning "week 3" into a list of document ids, or into nothing.
//
// **One query does the resolution and the evidence check at the same time, and that is the point
// of resolving to ids at all.** The alternative shape — read the course's taxonomy, decide whether
// the filter is honourable, then search with a predicate — is two questions asked of the same
// table one after another, and they can disagree: a document retired between the two reads makes
// the second answer describe a corpus the first one no longer matches. An empty result here *is*
// the answer to "does this course have a week 3", so there is nothing to keep in step.
//
// The predicates are the ones every retrieval branch already carries — the course, `READY`, and
// 16.3's visibility clause — because a scope that included a document retrieval cannot return
// would narrow the search to a document and then find none of it, which presents as a question
// that suddenly refuses.
@Repository
@RequiredArgsConstructor
public class TaxonomyScopeRepository {

    // A ceiling on how wide a narrowing may be. A filter matching most of the corpus is not a
    // narrowing: the ids would all be bound into every branch's `in (...)` for no change in what
    // comes back, which is a longer statement, a new plan per distinct count, and the same six
    // chunks. Sixty is well past any real week (a course puts a handful of files in one) and well
    // short of a corpus.
    private static final int MAX_SCOPE_DOCUMENTS = 60;

    private final JdbcTemplate jdbc;

    // The documents this filter names, or an empty list when the course has none — which the
    // caller reads as "this filter is not evidenced" and discards.
    public List<UUID> resolve(UUID courseId, UUID actorId, TaxonomyFilter filter) {
        if (filter.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select d.id
                from documents d
                where d.course_space_id = ?
                  and d.status = 'READY'
                  and (d.visibility = 'COURSE' or d.uploaded_by = ?)""");
        List<Object> args = new ArrayList<>(4);
        args.add(courseId);
        args.add(actorId);
        if (filter.week() != null) {
            sql.append("\n  and d.week_number = ?");
            args.add(filter.week());
        }
        if (filter.category() != null) {
            sql.append("\n  and d.category = ?");
            args.add(filter.category().name());
        }
        // Ordered so the id list is stable across calls: the scope becomes a bound parameter list
        // in six branches, and an unordered one would make two identical questions produce two
        // different statements for the planner to cache.
        sql.append("\norder by d.created_at, d.id\nlimit ?");
        args.add(MAX_SCOPE_DOCUMENTS + 1);

        List<UUID> ids = jdbc.query(sql.toString(),
                (rs, row) -> UUID.fromString(rs.getString("id")), args.toArray());
        // Over the ceiling the filter is treated as not narrowing anything, which is the same
        // answer as not evidenced and is handled by the same branch in the caller.
        return ids.size() > MAX_SCOPE_DOCUMENTS ? List.of() : ids;
    }
}
