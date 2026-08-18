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
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(documentId);
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
}
