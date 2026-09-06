package com.studyloop.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "studyloop.storage")
public record StorageProperties(
        // Which store holds uploaded document bytes: "filesystem" (the default) or "supabase".
        // The choice is a property of the deployment, not of the code — a host with a mounted
        // volume wants the filesystem; a free-tier container, whose disk is rebuilt on every
        // restart, wants object storage.
        String provider,

        // Filesystem root under which uploaded document bytes are stored. Relative to the
        // backend working directory in dev; a mounted volume path in the cloud. Unused when
        // the provider is "supabase".
        String documentsDir,

        // Supabase Storage settings. Required only when the provider is "supabase";
        // StorageConfig fails startup rather than first upload if any of them is missing.
        Supabase supabase
) {

    public record Supabase(
            // The project URL, e.g. https://<project-ref>.supabase.co
            String url,
            // The service-role key. Bypasses row-level security, so it is a server-side secret
            // and never reaches the browser.
            String serviceKey,
            // The (private) bucket that holds uploaded document bytes.
            String bucket
    ) { }
}
