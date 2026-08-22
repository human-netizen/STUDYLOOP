package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// Persists what the vision model read off a photographed note (Phase 16.3).
//
// Its own transactional bean for the same reason DocumentChunkService is: the ingestion
// orchestrator drives a status machine and commits each step separately, so a note whose blocks
// were written and whose embedding then failed shows FAILED with its transcription intact — and
// the student can see what was read rather than only that something went wrong.
@Service
@RequiredArgsConstructor
public class DocumentNoteBlockService {

    private final DocumentRepository documentRepository;
    private final DocumentNoteBlockRepository blockRepository;

    @Transactional
    public void replaceBlocks(UUID documentId, List<TranscribedBlock> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        // Re-reading a note replaces its blocks, exactly as re-ingesting replaces its chunks.
        // Flushed before the inserts so the deletes cannot collide with them on the
        // (document, ordinal) unique index.
        blockRepository.deleteByDocumentId(documentId);
        blockRepository.flush();

        for (TranscribedBlock source : blocks) {
            DocumentNoteBlock block = new DocumentNoteBlock();
            block.setDocument(document);
            block.setOrdinal(source.ordinal());
            block.setContent(source.content());
            block.setConfidence(source.confidence());
            block.setIndexed(source.indexed());
            blockRepository.save(block);
        }
    }

    @Transactional(readOnly = true)
    public List<TranscribedBlock> blocksOf(UUID documentId) {
        return blockRepository.findByDocumentIdOrderByOrdinal(documentId).stream()
                .map(block -> new TranscribedBlock(block.getOrdinal(), block.getContent(),
                        block.getConfidence(), block.isIndexed()))
                .toList();
    }
}
