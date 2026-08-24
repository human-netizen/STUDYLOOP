package com.studyloop.backend.video.dto;

import java.util.List;

// Everything the video page needs in one call: whether the feature exists here at all, whether
// the renderer is actually up, what the member has left today, and their jobs.
//
// **`enabled` is in the payload rather than in a global config endpoint** because it is the flag
// that decides whether the button is drawn, and a button drawn on a stale flag is exactly the
// dead UI the flag exists to prevent. One request, one truth, refetched by the same poll that
// updates the jobs.
//
// `workerReachable` is separate from `enabled` on purpose: the feature can be switched on in a
// development environment where Docker happens not to be running, and "the renderer is not
// running" is a different sentence from "this installation does not do videos" — one of them the
// person can fix in ten seconds.
public record VideoLibraryResponse(
        boolean enabled,
        boolean workerReachable,
        int dailyCap,
        int usedToday,
        List<VideoJobResponse> jobs
) { }
