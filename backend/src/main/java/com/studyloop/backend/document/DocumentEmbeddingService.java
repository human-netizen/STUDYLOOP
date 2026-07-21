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
            log.warn("Embedding provider not configured (set GOOGLE_API_KEY); document {} stored without vectors",
                    documentId);
            return;
        }

        // The query flushes any pending chunk inserts first (Hibernate auto-flush), so the
        // native UPDATE below sees rows written earlier in the same transaction.
        List<DocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndex(documentId);
        if (chunks.isEmpty()) {
            return;
        }

        List<float[]> vectors = embeddingClient.embed(chunks.stream().map(DocumentChunk::getContent).toList());
        if (vectors.size() != chunks.size()) {
            throw new EmbeddingException(
                    "Embedding count mismatch: expected " + chunks.size() + ", got " + vectors.size());
        }

        for (int i = 0; i < chunks.size(); i++) {
            jdbcTemplate.update("update document_chunks set embedding = cast(? as vector) where id = ?",
                    toVectorLiteral(vectors.get(i)), chunks.get(i).getId());
        }
    }

    // pgvector accepts a "[f1,f2,...]" text literal; Float.toString keeps a locale-safe dot.
    private static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}
