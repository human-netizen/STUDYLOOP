package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "studyloop.storage")
public record StorageProperties(
        // Filesystem root under which uploaded document bytes are stored. Relative to the
        // backend working directory in dev; a mounted volume path in the cloud.
        String documentsDir
) { }
