package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// Embeds a document's chunks and writes the vectors into the pgvector column. The column is
// intentionally unmapped in JPA (Hibernate has no vector type), so we write it with native
// SQL and a text-literal cast. When no provider is configured, this is a logged no-op — the
// chunks still exist and retrieval can fall back to full-text search (Phase 5).
@Service
@RequiredArgsConstructor
public class DocumentEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentEmbeddingService.class);

    private final EmbeddingClient embeddingClient;
    private final DocumentChunkRepository chunkRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void embedChunks(UUID documentId) {
        if (!embeddingClient.isConfigured()) {
            log.warn("Embedding provider not configured; document {} stored without vectors", documentId);
            return;
        }

        // The query flushes any pending chunk inserts first (Hibernate auto-flush), so the
        // native UPDATE below sees rows written earlier in the same transaction.
        //
        // Text chunks only, as of Phase 17. A visual chunk's vector is an embedding of its page
        // image; sending its `content` through the text embedder here would overwrite that with an
        // embedding of the caption, which is both the wrong vector and an invisible failure — the
        // row would still be found by *some* query, just never by the one it exists for.
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdAndModalityOrderByChunkIndex(
                documentId, ChunkModality.TEXT);
        if (chunks.isEmpty()) {
            return;
        }

        // The indexed text, not the displayed text: as of Phase 13.4 a chunk carries a context
        // header — its document's title and heading path — that belongs in the vector and not in
        // the passage a student reads. Pre-13 rows have no embed_text and fall back to content,
        // which is the same string this used to pass.
        List<float[]> vectors = embeddingClient.embed(chunks.stream().map(TextChunker::indexedText).toList());
        if (vectors.size() != chunks.size()) {
            throw new EmbeddingException(
                    "Embedding count mismatch: expected " + chunks.size() + ", got " + vectors.size());
        }

        for (int i = 0; i < chunks.size(); i++) {
            jdbcTemplate.update("update document_chunks set embedding = cast(? as vector) where id = ?",
                    VectorSupport.toLiteral(vectors.get(i)), chunks.get(i).getId());
        }
    }

    // Phase 17.1 — the page images, into the same column, in the same space.
    //
    // **Gated on the provider's capability rather than on a flag**, and that is the whole cost of
    // adding a modality: `embed-v4.0` puts pictures and text in one vector space, so there is no
    // second column, no second index and no second dimension. A provider that cannot — Google's
    // text-embedding-004, a local Ollama model — leaves the rows in place with no vector, exactly
    // as an unconfigured provider has left text chunks since Phase 4. Those rows cost nothing and
    // are found by nothing, which is the correct behaviour for a picture nobody can index.
    //
    // **Not fatal**, unlike a vision-extraction failure, and the difference is what the failure
    // leaves behind. A page whose scanned text was never read reaches READY unable to answer about
    // itself at all; a page whose *picture* was not embedded is exactly as answerable as it was in
    // Phase 16, because its text chunks are untouched. Failing the upload would trade a working
    // document for a missing improvement.
    @Transactional
    public void embedVisualChunks(UUID documentId, List<VisualChunk> visuals) {
        if (visuals.isEmpty()) {
            return;
        }
        if (!embeddingClient.isConfigured() || !embeddingClient.supportsImages()) {
            log.warn("Embedding provider cannot embed images; {} visual chunks of document {} "
                    + "are stored without vectors", visuals.size(), documentId);
            return;
        }

        // Matched by index rather than by position in a list this method was handed, because the
        // rows were written in a different transaction and the only thing tying the two together
        // is the chunk index the chunker assigned.
        List<DocumentChunk> rows = chunkRepository.findByDocumentIdAndModalityOrderByChunkIndex(
                documentId, ChunkModality.VISUAL);
        if (rows.size() != visuals.size()) {
            throw new EmbeddingException("Visual chunk count mismatch: expected " + visuals.size()
                    + " rows, found " + rows.size());
        }

        List<float[]> vectors;
        try {
            vectors = embeddingClient.embedImages(visuals.stream().map(VisualChunk::image).toList());
        } catch (RuntimeException e) {
            log.warn("Could not embed {} page images for document {}: {}",
                    visuals.size(), documentId, e.getMessage());
            return;
        }
        if (vectors.size() != visuals.size()) {
            throw new EmbeddingException("Image embedding count mismatch: expected "
                    + visuals.size() + ", got " + vectors.size());
        }

        for (int i = 0; i < rows.size(); i++) {
            jdbcTemplate.update("update document_chunks set embedding = cast(? as vector) where id = ?",
                    VectorSupport.toLiteral(vectors.get(i)), rows.get(i).getId());
        }
        log.info("Embedded {} page images for document {}", vectors.size(), documentId);
    }
}
