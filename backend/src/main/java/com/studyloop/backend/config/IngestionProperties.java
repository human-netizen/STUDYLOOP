package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Sizes the ingestion executor. These were fixed numbers inside AsyncConfig until the deploy
// phase, and they became configuration for one reason: the right value is a property of the
// machine, and the deploy target has 512 MB.
//
// Ingestion is the memory-hungry path in this application. PageImageRenderer rasterises a page
// at 150 DPI, which is roughly seven megabytes of BufferedImage for an A4 page before the PNG
// buffer beside it, and a routed document holds a PDDocument open across every page it renders.
// Two of those concurrently is comfortable on a laptop and is not survivable inside a 384 MB
// heap, so the free-tier deployment sets the core pool to one and ingests serially.
//
// The queue is what makes that acceptable rather than lossy: work waits instead of being
// rejected, and the upload still returns 202 immediately.
@ConfigurationProperties(prefix = "studyloop.ingestion")
public record IngestionProperties(
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity
) { }
