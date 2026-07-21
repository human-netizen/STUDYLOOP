package com.studyloop.backend.course;

// A user's role within a single course space. OWNER is the creator; INSTRUCTOR can
// manage content; MEMBER is a regular student. Distinct from the global auth Role.
public enum MembershipRole {
    OWNER,
    INSTRUCTOR,
    MEMBER
}
