package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "studyloop.cors")
public record CorsProperties(
        List<String> allowedOrigins
) { }
