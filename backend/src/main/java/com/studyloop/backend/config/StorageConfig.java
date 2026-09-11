package com.studyloop.backend.config;

import com.studyloop.backend.document.DocumentStorageService;
import com.studyloop.backend.document.FilesystemDocumentStorage;
import com.studyloop.backend.document.SupabaseDocumentStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Picks the single DocumentStorageService bean from studyloop.storage.provider, the same way
// EmbeddingConfig picks an EmbeddingClient: selecting here rather than annotating each
// implementation @Component keeps exactly one bean in the context, so the three callers inject
// the interface without ambiguity.
//
// Default: the filesystem, because that is what a development machine and a volume-mounted
// container both want, and because a default that needs three secrets is not a default.
@Configuration
public class StorageConfig {

    @Bean
    public DocumentStorageService documentStorageService(StorageProperties properties,
                                                        HttpProperties http) {
        String provider = properties.provider();
        if (provider != null && provider.equalsIgnoreCase("supabase")) {
            return new SupabaseDocumentStorage(requireSupabaseSettings(properties), http);
        }
        return new FilesystemDocumentStorage(properties);
    }

    // Fails startup, not the first upload. A container that boots healthy and then 500s the first
    // time a student uploads anything is the worst version of this mistake: the platform reports
    // the deploy as green, and the defect surfaces to a user rather than to a log.
    private static StorageProperties.Supabase requireSupabaseSettings(StorageProperties properties) {
        StorageProperties.Supabase supabase = properties.supabase();
        if (supabase == null
                || isBlank(supabase.url())
                || isBlank(supabase.serviceKey())
                || isBlank(supabase.bucket())) {
            throw new IllegalStateException(
                    "studyloop.storage.provider is \"supabase\", so studyloop.storage.supabase.url, "
                            + ".service-key and .bucket must all be set "
                            + "(SUPABASE_URL, SUPABASE_SERVICE_KEY, SUPABASE_BUCKET).");
        }
        return supabase;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
