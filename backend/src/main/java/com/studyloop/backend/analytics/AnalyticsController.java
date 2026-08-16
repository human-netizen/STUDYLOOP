package com.studyloop.backend.analytics;

import com.studyloop.backend.analytics.dto.ConfusionReport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// The instructor-facing side of the course: what the class asked, what it kept asking, and what
// the materials could not answer (Phase 9.1).
//
// Course-scoped rather than admin-scoped, unlike /admin/costs — this is a teaching tool for the
// person running one course, not an operator view. The role check lives in the service, next to
// every other course authorization decision.
@RestController
@RequestMapping("/api/v1/courses/{courseId}/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final ConfusionAnalyticsService analyticsService;

    // `days` bounds the counts, the lecture heat and the ungrounded list. Topic clusters ignore
    // it: they are computed over the newest N questions whatever their age, so a quiet course
    // still shows its topics instead of an empty list.
    @GetMapping("/confusion")
    public ConfusionReport confusion(Authentication authentication,
                                     @PathVariable UUID courseId,
                                     @RequestParam(name = "days", defaultValue = "30") int days) {
        return analyticsService.report(UUID.fromString(authentication.getName()), courseId, days);
    }
}
