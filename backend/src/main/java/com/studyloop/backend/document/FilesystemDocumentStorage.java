package com.studyloop.backend.document;

import com.studyloop.backend.config.StorageProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

// Writes uploaded document bytes to the local filesystem — the default, and the right answer
// anywhere the process owns a durable disk: a development machine, or a container with a volume
// mounted at DOCUMENTS_DIR.
//
// It is the wrong answer on a free-tier container, whose filesystem is rebuilt on every deploy
// and every wake from idle. See SupabaseDocumentStorage for that case.
public class FilesystemDocumentStorage implements DocumentStorageService {

    private final Path root;

    public FilesystemDocumentStorage(StorageProperties properties) {
        this.root = Path.of(properties.documentsDir()).toAbsolutePath().normalize();
    }

    @Override
    public String store(UUID courseId, String sha256, byte[] bytes) {
        String relativePath = courseId + "/" + sha256;
        Path target = root.resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new DocumentStorageException("Could not store the uploaded file.", e);
        }
        return relativePath;
    }

    @Override
    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(root.resolve(relativePath));
        } catch (IOException e) {
            throw new DocumentStorageException("Could not read stored document bytes.", e);
        }
    }

    // deleteIfExists rather than delete, so removing an object that is already gone succeeds —
    // see the interface for why the delete path depends on that. The empty course directory left
    // behind is deliberate: pruning it would race the next upload into the same course, and an
    // empty directory costs an inode.
    @Override
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(root.resolve(relativePath));
        } catch (IOException e) {
            throw new DocumentStorageException("Could not delete stored document bytes.", e);
        }
    }
}
