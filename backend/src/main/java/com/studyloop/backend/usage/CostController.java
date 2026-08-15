package com.studyloop.backend.usage;

import com.studyloop.backend.usage.dto.CostSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// What the app has spent on AI providers, and how much of that the semantic cache is avoiding.
//
// Admin-only, same as /admin/users: spend is an operator's concern, and the per-operation
// breakdown is a fairly precise map of which features are worth attacking. A non-admin token
// gets a 403 from the method-security check, not a 404 — the endpoint is no secret, the data is.
@RestController
@RequestMapping("/api/v1/admin/costs")
@RequiredArgsConstructor
public class CostController {

    private final CostService costService;

    // `days` bounds the spend figures only; the cache block is cumulative either way.
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public CostSummaryResponse costs(@RequestParam(name = "days", defaultValue = "30") int days) {
        return costService.summary(days);
    }
}
