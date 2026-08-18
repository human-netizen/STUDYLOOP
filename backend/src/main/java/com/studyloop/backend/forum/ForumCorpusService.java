package com.studyloop.backend.forum;

import com.studyloop.backend.chat.SemanticCacheService;
import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentChunkService;
import com.studyloop.backend.document.DocumentEmbeddingService;
import com.studyloop.backend.document.DocumentRepository;
import com.studyloop.backend.document.DocumentSource;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.DocumentStorageService;
import com.studyloop.backend.document.PageText;
import com.studyloop.backend.document.TextChunk;
import com.studyloop.backend.document.TextChunker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

// Writes an accepted answer back into the course's corpus, which is the half of Phase 9.2 that
// makes it a loop rather than a message board: the next student to ask gets the answer from chat.
//
// The text becomes an ordinary document — same table, same chunks, same embeddings, same hybrid
// retrieval — because everything downstream already works on documents, and a parallel "forum
// index" would mean a second retrieval path to fuse, to gate, and to keep in step. The only thing
// it lacks is a file, which is why `source` and a nullable `storage_path` exist.
//
// Synchronous, unlike a PDF upload. That is one chunk and one embedding call for a paragraph of
// text, not a hundred pages, so there is nothing worth handing to a worker and a 202 would only
// make the caller poll to learn something already true by the time it answered.
@Service
@RequiredArgsConstructor
public class ForumCorpusService {

    private static final Logger log = LoggerFactory.getLogger(ForumCorpusService.class);

    private static final String CONTENT_TYPE = "text/markdown";
    private static final String FILENAME_PREFIX = "Forum · ";
    private static final int MAX_FILENAME_LENGTH = 255;

    private final DocumentRepository documentRepository;
    private final DocumentStorageService storageService;
    private final TextChunker textChunker;
    private final DocumentChunkService chunkService;
    private final DocumentEmbeddingService embeddingService;
    private final SemanticCacheService semanticCache;

    // Returns the corpus document's id, or null if it could not be written.
    //
    // Failure is swallowed here, unlike the analytics write in ChatService: this one calls the
    // embedding provider, so it has an outage mode that has nothing to do with the decision being
    // recorded. Accepting an answer is a human judgement and must survive Cohere being down. The
    // thread's answer_document_id simply stays null, the UI does not claim the answer is in the
    // materials, and accepting again re-runs the write.
    public UUID publish(ForumThread thread, ForumAnswer answer) {
        try {
            return write(thread, answer);
        } catch (RuntimeException e) {
            log.warn("Could not add the accepted answer for thread {} to the corpus: {}",
                    thread.getId(), e.getMessage());
            return null;
        }
    }

    private UUID write(ForumThread thread, ForumAnswer answer) {
        UUID courseId = thread.getCourseSpace().getId();
        String text = compose(thread, answer);

        // Keyed on the thread, not on the text. sha256 means "the same content" everywhere else in
        // this table, but here the identity that matters is the discussion: accepting a different
        // reply, or the same one twice, has to replace this thread's document rather than leave a
        // second copy of a superseded answer in the corpus. The (course, sha256) unique index then
        // enforces one document per thread for free.
        String sha256 = storageService.sha256Hex(("forum:" + thread.getId()).getBytes(StandardCharsets.UTF_8));

        Document document = documentRepository.findByCourseSpaceIdAndSha256(courseId, sha256)
                .orElseGet(Document::new);
        document.setCourseSpace(thread.getCourseSpace());
        // Provenance, not permission: the answer's author, so the row says where the text came
        // from even though it was a manager who let it in.
        document.setUploadedBy(answer.getCreatedBy());
        document.setFilename(filenameFor(thread));
        document.setContentType(CONTENT_TYPE);
        document.setSizeBytes(text.getBytes(StandardCharsets.UTF_8).length);
        document.setSha256(sha256);
        document.setSource(DocumentSource.FORUM);
        document.setStoragePath(null);
        // READY without passing through the ingestion pipeline, because the two steps that matter
        // — chunk and embed — happen right here and the two that don't (fetch the file, extract
        // its text) have nothing to do.
        document.setStatus(DocumentStatus.READY);
        document.setErrorMessage(null);
        documentRepository.saveAndFlush(document);

        chunkService.replaceChunks(document.getId(), chunk(text), 0);
        embeddingService.embedChunks(document.getId());

        // The corpus just grew, so answers cached against the old one are suspect — most of all
        // the refusals this thread exists to fix. Same reasoning as ingesting a document.
        semanticCache.invalidate(courseId);
        return document.getId();
    }

    // The question goes into the chunk with the answer, and that is the mechanism rather than a
    // nicety: the text has to be findable by the question that failed, and an answer on its own
    // usually shares no vocabulary with it ("Use the residue theorem" against "why does this
    // integral vanish?"). Embedding the pair puts the question's own words in the corpus.
    //
    // No author name in the text. The model answers from the sources it is given and will quote
    // whatever is in them; whose reply it was belongs in the forum, not in a citation.
    private static String compose(ForumThread thread, ForumAnswer answer) {
        StringBuilder text = new StringBuilder();
        text.append("Question: ").append(thread.getTitle().strip()).append("\n\n");
        if (thread.getBody() != null && !thread.getBody().isBlank()) {
            text.append(thread.getBody().strip()).append("\n\n");
        }
        text.append("Answer: ").append(answer.getBody().strip()).append('\n');
        return text.toString();
    }

    // Page numbers are stripped afterwards: this text never came from a page, and citing "p.1"
    // would invite a click through to a file that does not exist. Page count 0 for the same reason.
    //
    // No context header title, unlike an uploaded document. compose() already opens the text with
    // the question it answers, so the words a student would search for are in the passage itself;
    // prefixing "Forum · <the same question>" would put a second copy of them in the index and
    // weight the chunk toward its own title.
    private List<TextChunk> chunk(String text) {
        return textChunker.chunk(List.of(new PageText(1, text))).stream()
                .map(TextChunk::withoutPages)
                .toList();
    }

    private static String filenameFor(ForumThread thread) {
        String name = FILENAME_PREFIX + thread.getTitle().replaceAll("\\s+", " ").strip();
        return name.length() > MAX_FILENAME_LENGTH ? name.substring(0, MAX_FILENAME_LENGTH) : name;
    }
}
