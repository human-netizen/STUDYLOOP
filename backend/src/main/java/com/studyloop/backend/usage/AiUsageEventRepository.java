package com.studyloop.backend.usage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// Writes only. The dashboard's aggregates are native SQL in AiUsageStatsRepository, because
// grouping by day needs date_trunc and JPQL has no equivalent.
public interface AiUsageEventRepository extends JpaRepository<AiUsageEvent, UUID> {
}
