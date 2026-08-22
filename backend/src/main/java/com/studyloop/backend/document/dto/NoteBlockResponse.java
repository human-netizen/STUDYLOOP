package com.studyloop.backend.document.dto;

import com.studyloop.backend.document.TranscribedBlock;

// One block of a digitised note, as the review view shows it (Phase 16.3).
//
// The `indexed` flag is the field the whole screen is built around. A block with it false is text
// the model read and was not sure enough about to put in the course's index — it is shown, it can
// be read, and it answers nothing. That is the difference between a threshold that protects the
// corpus and one that quietly eats a third of somebody's notes.
public record NoteBlockResponse(int ordinal, String content, double confidence, boolean indexed) {

    public static NoteBlockResponse from(TranscribedBlock block) {
        return new NoteBlockResponse(
                block.ordinal(), block.content(), block.confidence(), block.indexed());
    }
}
