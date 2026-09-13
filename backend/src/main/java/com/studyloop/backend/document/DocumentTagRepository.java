package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Phase 23.2 — the labels on a document, stored as rows and read as a set.
//
// **Not a JPA entity, and not mapped on `Document` as an `@ElementCollection`.** Both were
// considered and both lose the same thing: `document_tags` has a composite natural key and no
// state of its own, so mapping it buys an `@IdClass` or an `@EmbeddedId` in exchange for a lazy
// collection that has to be initialised inside a transaction — and this codebase runs
// `open-in-view: false`, so a DTO built one layer up would throw. Two `JdbcTemplate` statements
// say the same thing with no identity to manage.
//
// **The consequence to know about is the delete path**, and here it goes the right way.
// `DocumentLifecycleService.delete` removes the children this package *maps* by hand, because a
// database cascade is invisible to Hibernate's persistence context. Tags are not mapped, so no
// persistence context is holding one, and `on delete cascade` is exactly the right mechanism —
// the same clause that is a hazard for a mapped child is the correct answer for an unmapped one.
//
// **Reads are batched by construction.** `tagsOf(List)` exists rather than a per-document lookup
// because every caller is rendering a course's library, and fourteen documents rendered with a
// per-row query is the N+1 that Phase 26.2 spent a phase removing from the chat path.
@Repository
@RequiredArgsConstructor
public class DocumentTagRepository {

    // Long enough for "dynamic programming" and "tree traversal", short enough that a tag cannot
    // become a sentence. Matches the column.
    static final int MAX_TAG_LENGTH = 40;

    private final JdbcTemplate jdbc;

    // Lower case, single-spaced, trimmed, de-duplicated, order preserved.
    //
    // **Normalisation happens here rather than in the database**, because it has to be the same
    // function for the write and for any later read that compares a string to a stored tag — and
    // a `lower(tag)` expression index would make the column's *stored* value and its *matched*
    // value two different things, which is the state where "Recursion" and "recursion" are one
    // tag in a query and two on screen.
    public static Set<String> normalize(List<String> tags) {
        if (tags == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String clean = tag.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (clean.isEmpty()) {
                continue;
            }
            normalized.add(clean.length() > MAX_TAG_LENGTH
                    ? clean.substring(0, MAX_TAG_LENGTH).trim()
                    : clean);
        }
        return normalized;
    }

    public Set<String> tagsOf(UUID documentId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "select tag from document_tags where document_id = ? order by tag",
                String.class, documentId));
    }

    // One query for a whole library page. Documents with no tags are absent from the map rather
    // than present with an empty set, so the caller's `getOrDefault` is the only place the empty
    // case is spelled.
    public Map<UUID, Set<String>> tagsOf(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = documentIds.stream()
                .map(id -> "cast(? as uuid)")
                .collect(Collectors.joining(", "));
        Map<UUID, Set<String>> byDocument = new LinkedHashMap<>();
        jdbc.query("""
                select document_id, tag
                from document_tags
                where document_id in (%s)
                order by document_id, tag
                """.formatted(placeholders), rs -> {
            UUID documentId = UUID.fromString(rs.getString("document_id"));
            byDocument.computeIfAbsent(documentId, key -> new LinkedHashSet<>())
                    .add(rs.getString("tag"));
        }, documentIds.toArray());
        return byDocument;
    }

    // Every distinct tag in a course, for the picker and for the library filter. Ordered by name
    // rather than by frequency: a picker whose entries move when somebody else tags a document is
    // a picker you have to read every time.
    public List<String> tagsInCourse(UUID courseId) {
        return jdbc.queryForList("""
                select distinct t.tag
                from document_tags t
                join documents d on d.id = t.document_id
                where d.course_space_id = ?
                order by t.tag
                """, String.class, courseId);
    }

    // Replace, not merge — the PATCH body carries the whole tag set when it carries tags at all,
    // because a partial update of a *set* has no single obvious meaning and the two candidates
    // (add these / these are now all of them) would be indistinguishable on the wire.
    public void replace(UUID documentId, Set<String> tags) {
        jdbc.update("delete from document_tags where document_id = ?", documentId);
        if (tags.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(tags.size());
        for (String tag : tags) {
            rows.add(new Object[] {documentId, tag});
        }
        jdbc.batchUpdate("insert into document_tags (document_id, tag) values (?, ?)", rows);
    }
}
