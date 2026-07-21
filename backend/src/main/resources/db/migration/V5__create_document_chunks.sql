create table document_chunks (
    id           uuid         primary key,
    document_id  uuid         not null references documents (id) on delete cascade,
    chunk_index  integer      not null,
    page_number  integer,
    content      text         not null,
    token_count  integer      not null,
    created_at   timestamptz  not null default now(),
    constraint uq_chunk_document_index unique (document_id, chunk_index)
);

create index idx_document_chunks_document on document_chunks (document_id);
