package com.studyloop.backend.document;

import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.document.CourseOutlineRepository.DocumentRow;
import com.studyloop.backend.document.CourseOutlineRepository.SectionRow;
import com.studyloop.backend.document.CourseOutlineRepository.TermRow;
import com.studyloop.backend.document.dto.CourseOutline;
import com.studyloop.backend.document.dto.CourseOutline.DocumentOutline;
import com.studyloop.backend.document.dto.CourseOutline.SectionOutline;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Phase 28.4 — the course as a table of contents rather than a folder listing.
//
// Three queries and no model call. The assembly below is a grouping, not a computation: the
// database returns sections in document order and terms in the order the glossary generated them,
// and this class walks both once and hangs them off the documents.
@Service
@RequiredArgsConstructor
public class CourseOutlineService {

    private final CourseAccess courseAccess;
    private final CourseOutlineRepository repository;

    // Any course member. This is the student-facing view of the corpus — the manager-only page is
    // `/analytics/confusion`, which answers the opposite question.
    @Transactional(readOnly = true)
    public CourseOutline outline(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);

        List<DocumentRow> documents = repository.documents(courseId, actorId);
        if (documents.isEmpty()) {
            return new CourseOutline(List.of(), 0);
        }

        // section_path -> its terms, keyed by the pair the SQL grouped on. LinkedHashMap
        // throughout, because the order these arrive in is the order the page renders and
        // re-sorting a section list alphabetically would put "10. Treaps" before "2. Arrays".
        Map<SectionKey, List<String>> termsBySection = new LinkedHashMap<>();
        for (TermRow term : repository.sectionTerms(courseId, actorId)) {
            termsBySection
                    .computeIfAbsent(new SectionKey(term.documentId(), term.sectionPath()),
                            key -> new ArrayList<>())
                    .add(term.term());
        }

        Map<UUID, List<SectionOutline>> sectionsByDocument = new LinkedHashMap<>();
        for (SectionRow section : repository.sections(courseId, actorId)) {
            List<String> terms = termsBySection.getOrDefault(
                    new SectionKey(section.documentId(), section.sectionPath()), List.of());
            sectionsByDocument
                    .computeIfAbsent(section.documentId(), key -> new ArrayList<>())
                    .add(new SectionOutline(section.sectionPath(), section.firstPage(),
                            section.lastPage(), section.chunkCount(), terms));
        }

        List<DocumentOutline> outlines = new ArrayList<>(documents.size());
        int neverAsked = 0;
        for (DocumentRow document : documents) {
            if (document.questionCount() == 0) {
                neverAsked++;
            }
            outlines.add(new DocumentOutline(document.documentId(), document.filename(),
                    document.pageCount(), document.chunkCount(), document.questionCount(),
                    sectionsByDocument.getOrDefault(document.documentId(), List.of())));
        }
        return new CourseOutline(outlines, neverAsked);
    }

    // The pair both the section query and the term query group on. A record rather than a
    // concatenated string so the key that goes in is the key that comes out — the same reason
    // `ChunkSearchRepository.SectionKey` exists one package over.
    private record SectionKey(UUID documentId, String sectionPath) { }
}
