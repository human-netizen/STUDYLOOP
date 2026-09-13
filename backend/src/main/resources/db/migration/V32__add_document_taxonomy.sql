-- Phase 23.2 — the three things a course's materials are organised by, and the one thing they
-- are labelled with.
--
-- Documents have been flat since V4: a filename, a status and a course. A course with fourteen
-- lectures, their labs and two past papers has a structure its author already knows and the
-- database has never been told, so every question searches all of it and the library page is a
-- list of filenames in upload order.
--
-- **Two columns and a table, and the split is the difference between a fact and a label.**
-- `week_number` and `category` are single-valued properties of the material — a handout belongs
-- to one week and is one kind of thing — so they are columns, and a column is what a filter
-- predicate and an index want. A tag is a label a person chose, there can be several, and the set
-- is open, which is a row per value and not a widening column.

alter table documents
    -- Which week of the course this material belongs to, 1-based, in the course's own numbering.
    --
    -- Null is not "week zero" and not a default: it means nobody has said. Every row that existed
    -- before this migration is null, and so is every upload whose filename does not say — which
    -- matters because the query-side filter treats "the course has no week metadata" and "the
    -- course has no week 3" as the same answer, and both of those are null-shaped.
    add column week_number integer,

    -- What kind of material it is. UNCLASSIFIED rather than null, because unlike the week this
    -- one is never absent — a document is always *some* kind of thing, and "we have not worked
    -- out which" is a value with a name rather than a gap. It also keeps the column `not null`,
    -- so a category filter is one equality and never a three-valued comparison.
    add column category varchar(20) not null default 'UNCLASSIFIED',

    add constraint ck_documents_week_number
        check (week_number is null or week_number between 1 and 52);

-- Open-vocabulary labels, normalised to lower case by the application before they arrive.
--
-- **No surrogate id, and the pair is the primary key.** There is nothing to say about a tag
-- beyond which document carries it, so a synthetic key would exist only to permit the duplicate
-- row the natural key forbids. `(document_id, tag)` also leads with the column every read filters
-- on, so "this document's tags" is an index-only scan and needs no second index.
create table document_tags (
    document_id uuid        not null references documents (id) on delete cascade,
    tag         varchar(40) not null,
    primary key (document_id, tag)
);

-- The other direction — "which documents carry this tag" — which the primary key cannot answer
-- because tag is its trailing column.
create index idx_document_tags_tag on document_tags (tag);

-- Both filters are always course-scoped, so the course leads both indexes: a filter that did not
-- name a course would be a cross-course read and there is no such query in this codebase.
--
-- The week index is partial. Nulls are the majority of this column and will stay the majority —
-- a course that labels its materials labels some of them — and a row that has not said which week
-- it belongs to can never satisfy `week_number = ?`, so indexing it is bytes spent on rows the
-- predicate excludes by definition.
create index idx_documents_course_week on documents (course_space_id, week_number)
    where week_number is not null;

create index idx_documents_course_category on documents (course_space_id, category);

comment on column documents.week_number is
    'Which week of the course this material belongs to (Phase 23.2), 1-based, null when nobody '
        'has said. Inferred from the filename at upload and correctable by hand — an inferred '
        'value is a default, not a fact, which is why the correction path exists before the '
        'inference does.';

comment on column documents.category is
    'What kind of material this is (Phase 23.2): LECTURE, LAB, TUTORIAL, ASSIGNMENT, EXAM, '
        'READING or UNCLASSIFIED. Never null — "not worked out yet" is a value with a name, so a '
        'category filter stays one equality rather than a three-valued comparison.';

comment on table document_tags is
    'Open-vocabulary labels on a document (Phase 23.2), lower-cased by the application. Set by a '
        'person and never inferred: a tag read off the content would be a guess presented as a '
        'label somebody chose.';
