-- pgvector was enabled during project setup; this is a no-op if already present.
create extension if not exists vector;

-- Google text-embedding-004 produces 768-dimensional vectors.
alter table document_chunks add column embedding vector(768);

-- HNSW index for fast approximate nearest-neighbour search by cosine distance (Phase 5).
create index idx_document_chunks_embedding
    on document_chunks using hnsw (embedding vector_cosine_ops);
