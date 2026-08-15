package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "studyloop.summary")
public record SummaryProperties(
        // Whether ingestion generates a document's summary + glossary automatically once it
        // reaches READY. One model call per document, cached forever after. Turning it off keeps
        // bulk imports and test runs off the provider; the summary endpoint still generates on
        // demand.
        boolean autoGenerate
) { }
