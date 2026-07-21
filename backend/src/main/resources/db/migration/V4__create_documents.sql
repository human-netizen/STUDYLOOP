create table documents (
    id              uuid          primary key,
    course_space_id uuid          not null references course_spaces (id) on delete cascade,
    filename        varchar(255)  not null,
    content_type    varchar(100)  not null,
    size_bytes      bigint        not null,
    sha256          varchar(64)   not null,
    storage_path    varchar(512)  not null,
    page_count      integer,
    status          varchar(20)   not null default 'UPLOADED',
    error_message   varchar(500),
    uploaded_by     uuid          not null references users (id),
    created_at      timestamptz   not null default now(),
    updated_at      timestamptz   not null default now(),
    constraint uq_document_course_sha256 unique (course_space_id, sha256)
);

create index idx_documents_course on documents (course_space_id);
