package com.studyloop.backend.video.dto;

import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.video.SceneRendering;

import java.util.List;

// One scene as the player sees it.
//
// `citations` are chat's own Citation records, so the source rail and the jump-to-page viewer are
// Phase 6.2 and 11.2's components rather than new ones — and so a claim in a video can be checked
// against the page it came from, which is the difference between this and the narrated slideshow
// AddNewFeature.md §4 objected to.
public record VideoSceneResponse(
        int index,
        String title,
        // Also the caption text for this scene, so a client that cannot play the VTT track can
        // still show what is being said.
        String narration,
        SceneRendering renderedAs,
        // Null unless an animation was attempted and lost. Shown, not swallowed: the layer that
        // stopped it and what the toolchain said.
        String fallbackReason,
        Double durationSeconds,
        List<Citation> citations
) { }
