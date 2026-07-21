package com.studyloop.backend.course;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CourseSpaceRepository extends JpaRepository<CourseSpace, UUID> {
}
