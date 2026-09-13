package com.studyloop.backend.document;

import com.studyloop.backend.chat.SemanticCacheService;
import com.studyloop.backend.course.CourseAccess;
import com.studyloop.backend.course.InsufficientCourseRoleException;
import com.studyloop.backend.course.Membership;
import com.studyloop.backend.course.MembershipRole;
import com.studyloop.backend.document.dto.CourseTaxonomy;
import com.studyloop.backend.document.dto.DocumentResponse;
import com.studyloop.backend.document.dto.DocumentTaxonomyRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

// Phase 23.2 — saying what a document is, and reading back what a course is organised by.
//
// **The correction path exists before the inference does, and that ordering is the argument for
// inferring at all.** `DocumentTaxonomyInferrer` reads a week and a category off a filename; it
// is right on a course that names its files and silent on one that does not. Either outcome is
// acceptable *because* this class is here: an inferred value is a default somebody overwrites in
// one request. Shipping the inference without the correction would have made a filename typo into
// a retrieval defect with no way to fix it short of a re-upload.
@Service
@RequiredArgsConstructor
public class DocumentTaxonomyService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTaxonomyService.class);

    private final DocumentRepository documentRepository;
    private final DocumentTagRepository tagRepository;
    private final SemanticCacheService semanticCache;
    private final CourseAccess courseAccess;

    // What this course has material under. Any member may read it — it is the shape of the
    // library they can already see, not a permission.
    @Transactional(readOnly = true)
    public CourseTaxonomy courseTaxonomy(UUID actorId, UUID courseId) {
        courseAccess.requireMember(actorId, courseId);
        return new CourseTaxonomy(
                documentRepository.weeksInCourse(courseId),
                documentRepository.categoriesInCourse(courseId),
                tagRepository.tagsInCourse(courseId));
    }

    // The whole taxonomy, stated. See DocumentTaxonomyRequest for why this is a PUT.
    //
    // **The semantic cache is invalidated, and it is the same reasoning retire and un-retire
    // use.** A cached answer was produced against the corpus as it was scoped at the time. Moving
    // a document into week 3 changes which documents a question about week 3 searches, so an entry
    // written before the move answers from a scope that no longer exists — and it would go on
    // being served, with no error anywhere, until it expired.
    @Transactional
    public DocumentResponse update(UUID actorId, UUID courseId, UUID documentId,
                                   DocumentTaxonomyRequest request) {
        Document document = require(actorId, courseId, documentId);

        document.setWeekNumber(request.week());
        document.setCategory(request.category() == null
                ? DocumentCategory.UNCLASSIFIED
                : request.category());
        documentRepository.saveAndFlush(document);

        Set<String> tags = DocumentTagRepository.normalize(request.tags());
        tagRepository.replace(documentId, tags);

        semanticCache.invalidate(courseId);
        log.info("Taxonomy set on document {} in course {}: week={} category={} tags={}",
                documentId, courseId, document.getWeekNumber(), document.getCategory(), tags.size());
        return DocumentResponse.from(document, courseId, tags);
    }

    // Who may label a document, and it is deliberately the same rule as who may retire one
    // (Phase 27.3's `require`). A taxonomy is not cosmetic: it decides what a question aimed at
    // week 3 retrieves for everybody in the course, so on course-visible material it is a
    // manager's call. A private note is its owner's to label, because narrowing to it narrows to
    // material only they can be answered from anyway.
    private Document require(UUID actorId, UUID courseId, UUID documentId) {
        Membership actor = courseAccess.requireMember(actorId, courseId);
        Document document = documentRepository.findVisibleById(documentId, courseId, actorId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        boolean mine = document.getUploadedBy().getId().equals(actorId);
        boolean privateNote = document.getVisibility() == DocumentVisibility.OWNER;
        if (privateNote && mine) {
            return document;
        }
        if (actor.getRole() == MembershipRole.MEMBER) {
            throw new InsufficientCourseRoleException(courseId);
        }
        return document;
    }
}
