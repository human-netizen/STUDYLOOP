package com.studyloop.backend.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VideoSceneRepository extends JpaRepository<VideoScene, UUID> {

    List<VideoScene> findByJobIdOrderBySceneIndex(UUID jobId);

    void deleteByJobId(UUID jobId);
}
