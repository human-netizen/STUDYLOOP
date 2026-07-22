package com.studyloop.backend.quiz;

// The two question kinds a generated quiz can hold. MULTIPLE_CHOICE is graded deterministically
// by comparing the picked option index; SHORT_ANSWER is graded by the model against the expected
// answer (Phase 7.2), since free text can be phrased many ways.
public enum QuestionType {
    MULTIPLE_CHOICE,
    SHORT_ANSWER
}
