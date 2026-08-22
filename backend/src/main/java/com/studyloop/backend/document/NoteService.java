package com.studyloop.backend.document;

import com.studyloop.backend.chat.SemanticCacheService;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.document.DocumentService.UploadOutcome;
import com.studyloop.backend.document.dto.NoteBlockResponse;
import com.studyloop.backend.document.dto.NoteResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

// Handwritten notes as first-class documents (Phase 16.3).
//
// **What is deliberately not here.** No chunker, no embedder, no retrieval, no citation format, no
// quiz or flashcard integration, no status machine. A note is a `Document`, so it already has all
// of those — the endpoints below are an upload with different permissions, a list, a review view
// and a promotion. That is the claim the phase makes and this class is the evidence for it: closing
// the loop from a digitised note back into the corpus costs an access rule, not a pipeline.
//
// **Any member may upload; only a manager may promote.** Uploading is writing in your own notebook
// and the course's corpus is untouched by it. Promoting writes member-authored text into the set
// that grounds and is cited by every future answer for everyone — the same act as accepting a forum
// answer in 9.2, gated the same way, and for the reason that matters more here: a promoted note
// carries whatever the vision model guessed about somebody's handwriting.
@Service
@RequiredArgsConstructor
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final DocumentNoteBlockService noteBlockService;
    private final CourseAccess courseAccess;
    private final SemanticCacheService semanticCache;

    // requireMember, not requireManager. This is the one document a student may add to a course,
    // and it is theirs: it starts owner-visible, so "adding" it changes nothing anybody else can
    // see or be answered from until a manager says so.
    @Transactional
    public UploadOutcome upload(UUID actorId, UUID courseId,
                                org.springframework.web.multipart.MultipartFile file) {
        Membership actor = courseAccess.requireMember(actorId, courseId);
        return documentService.accept(actor, courseId, file, DocumentFormat.imageFormats(),
                DocumentSource.HANDWRITTEN, DocumentVisibility.OWNER);
    }

    @Transactional(readOnly = true)
    public List<NoteResponse> list(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        return documentRepository.findVisibleNotes(courseId, actorId).stream()
                .map(note -> NoteResponse.from(note, courseId, actorId))
                .toList();
    }

    // The review view: every block the model read, kept or dropped, in the order they were on the
    // page. This is what makes the confidence threshold honest rather than merely cautious — the
    // blocks below it are not in the index and are not lost, and the person who wrote the page can
    // see exactly which lines the model could not read.
    @Transactional(readOnly = true)
    public List<NoteBlockResponse> blocks(UUID actorId, UUID courseId, UUID noteId) {
        readable(actorId, courseId, noteId);
        return noteBlockService.blocksOf(noteId).stream()
                .map(NoteBlockResponse::from)
                .toList();
    }

    // 16.3's LaTeX export. Only the indexed blocks: a document handed to somebody as their notes
    // must not silently contain the lines the model was unsure of, which is the same rule the
    // corpus follows and for a stronger reason — an exported .tex leaves the system, and nothing
    // travels with it to say which sentences were guesses.
    @Transactional(readOnly = true)
    public String latex(UUID actorId, UUID courseId, UUID noteId) {
        Document note = readable(actorId, courseId, noteId);
        String markdown = noteBlockService.blocksOf(noteId).stream()
                .filter(TranscribedBlock::indexed)
                .map(TranscribedBlock::content)
                .collect(Collectors.joining("\n\n"));
        if (markdown.isBlank()) {
            throw new NoteNotReadyException(noteId);
        }
        return LatexExport.of(stemOf(note.getFilename()), markdown);
    }

    // Manager-gated, and it changes what everyone in the course can be answered from — so the
    // course's cached answers stop being answers to the corpus that now exists. Phase 8.3's cache
    // is invalidated for the same reason ingestion invalidates it, and most of all for the
    // refusals: this note may be exactly the thing that made an unanswerable question answerable.
    @Transactional
    public NoteResponse promote(UUID actorId, UUID courseId, UUID noteId) {
        courseAccess.requireManager(actorId, courseId);
        Document note = noteOf(courseId, noteId);
        if (note.getStatus() != DocumentStatus.READY) {
            throw new NoteNotReadyException(noteId);
        }
        if (note.getVisibility() != DocumentVisibility.COURSE) {
            note.setVisibility(DocumentVisibility.COURSE);
            documentRepository.saveAndFlush(note);
            semanticCache.invalidate(courseId);
            log.info("Promoted note {} to course material in course {}", noteId, courseId);
        }
        return NoteResponse.from(note, courseId, actorId);
    }

    // Demotion, which exists because promotion is irreversible without it and a manager who
    // promoted the wrong note has no other way back. It does not delete anything: the note returns
    // to being its author's, which is what it was.
    @Transactional
    public NoteResponse demote(UUID actorId, UUID courseId, UUID noteId) {
        courseAccess.requireManager(actorId, courseId);
        Document note = noteOf(courseId, noteId);
        if (note.getVisibility() != DocumentVisibility.OWNER) {
            note.setVisibility(DocumentVisibility.OWNER);
            documentRepository.saveAndFlush(note);
            semanticCache.invalidate(courseId);
            log.info("Demoted note {} back to its owner in course {}", noteId, courseId);
        }
        return NoteResponse.from(note, courseId, actorId);
    }

    // A note the caller may read: their own, one already promoted, or any note if they manage the
    // course — a manager has to be able to read what they are being asked to promote.
    private Document readable(UUID actorId, UUID courseId, UUID noteId) {
        Membership actor = courseAccess.requireMember(actorId, courseId);
        Document note = noteOf(courseId, noteId);
        boolean mine = note.getUploadedBy().getId().equals(actorId);
        boolean promoted = note.getVisibility() == DocumentVisibility.COURSE;
        boolean manages = actor.getRole() != MembershipRole.MEMBER;
        if (!mine && !promoted && !manages) {
            // 404 rather than 403: whether a classmate has photographed a particular page is
            // itself private, and 403 would confirm the note exists.
            throw new DocumentNotFoundException(noteId);
        }
        return note;
    }

    private Document noteOf(UUID courseId, UUID noteId) {
        Document note = documentRepository.findByIdAndCourseSpaceId(noteId, courseId)
                .orElseThrow(() -> new DocumentNotFoundException(noteId));
        if (note.getSource() != DocumentSource.HANDWRITTEN) {
            // A course material id sent to a notes endpoint. Not found, because it is not a note.
            throw new DocumentNotFoundException(noteId);
        }
        return note;
    }

    private static String stemOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return "Note";
        }
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        return stem.replaceAll("[_-]+", " ").replaceAll("\\s+", " ").strip();
    }
}
