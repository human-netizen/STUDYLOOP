package com.studyloop.backend.config;

import com.studyloop.backend.document.DocumentStorageService;
import com.studyloop.backend.document.FilesystemDocumentStorage;
import com.studyloop.backend.document.SupabaseDocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Phase 23.4 — the storage provider switch, and the startup failure that is the point of it.
//
// The deployment target has no persistent disk, so which implementation is wired is a deployment
// decision expressed as a property. Two things have to hold for that to be safe: the property has
// to actually select, and a "supabase" deployment missing its credentials has to fail loudly at
// startup rather than quietly at a student's first upload.
class StorageConfigTest {

    private final StorageConfig config = new StorageConfig();

    private static StorageProperties properties(String provider, StorageProperties.Supabase supabase) {
        return new StorageProperties(provider, "./data/documents", supabase);
    }

    private static StorageProperties.Supabase complete() {
        return new StorageProperties.Supabase("https://ref.supabase.co", "service-role-key", "documents");
    }

    @Test
    void defaultsToTheFilesystem() {
        // Unset, and every unrecognised value, means the filesystem: a default that needs three
        // secrets is not a default, and a dev machine has a disk.
        assertThat(config.documentStorageService(properties(null, null), HttpProperties.defaults()))
                .isInstanceOf(FilesystemDocumentStorage.class);
        assertThat(config.documentStorageService(properties("filesystem", null), HttpProperties.defaults()))
                .isInstanceOf(FilesystemDocumentStorage.class);
    }

    @Test
    void selectsSupabaseWhenAskedTo() {
        assertThat(config.documentStorageService(properties("supabase", complete()), HttpProperties.defaults()))
                .isInstanceOf(SupabaseDocumentStorage.class);
        // The platform sets this from a dashboard field, so case is not something to rely on.
        assertThat(config.documentStorageService(properties("SUPABASE", complete()), HttpProperties.defaults()))
                .isInstanceOf(SupabaseDocumentStorage.class);
    }

    @Test
    void refusesToStartWhenSupabaseIsSelectedWithoutItsSettings() {
        // Each of these boots healthy and passes a health check under a lazier check, then 500s
        // the first time anybody uploads anything. The deploy would be reported green.
        assertThatThrownBy(() -> config.documentStorageService(properties("supabase", null), HttpProperties.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPABASE_URL");

        assertThatThrownBy(() -> config.documentStorageService(
                properties("supabase", new StorageProperties.Supabase("", "key", "documents")),
                HttpProperties.defaults()))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> config.documentStorageService(
                properties("supabase", new StorageProperties.Supabase("https://ref.supabase.co", " ", "documents")),
                HttpProperties.defaults()))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> config.documentStorageService(
                properties("supabase", new StorageProperties.Supabase("https://ref.supabase.co", "key", null)),
                HttpProperties.defaults()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bothImplementationsAddressBytesTheSameWay(@TempDir Path tempDir) {
        // The storage_path column has to mean one thing under either provider, or moving a
        // deployment between them is a migration. Content-addressed, course-scoped, relative.
        DocumentStorageService filesystem =
                new FilesystemDocumentStorage(new StorageProperties("filesystem", tempDir.toString(), null));

        UUID courseId = UUID.randomUUID();
        byte[] bytes = "lecture 7".getBytes();
        String sha256 = filesystem.sha256Hex(bytes);

        assertThat(filesystem.store(courseId, sha256, bytes)).isEqualTo(courseId + "/" + sha256);
        assertThat(filesystem.read(courseId + "/" + sha256)).isEqualTo(bytes);
    }

    @Test
    void hashingIsProviderIndependent() {
        // Defaulted on the interface rather than implemented twice, so this is the assertion that
        // it stays one function: a hash that differed by provider would silently break dedup.
        DocumentStorageService filesystem =
                new FilesystemDocumentStorage(new StorageProperties("filesystem", "./data/documents", null));
        DocumentStorageService supabase = new SupabaseDocumentStorage(complete(), HttpProperties.defaults());

        byte[] bytes = "the same nine bytes".getBytes();
        assertThat(supabase.sha256Hex(bytes)).isEqualTo(filesystem.sha256Hex(bytes));
    }
}
