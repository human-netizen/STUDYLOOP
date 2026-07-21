package com.studyloop.backend.document;

import com.studyloop.backend.config.StorageProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

// Writes uploaded document bytes to the local filesystem and computes their content hash.
// Bytes live at {documentsDir}/{courseId}/{sha256}; we persist the relative
// "{courseId}/{sha256}" on the entity so the root can move between environments.
@Service
public class DocumentStorageService {

    private final Path root;

    public DocumentStorageService(StorageProperties properties) {
        this.root = Path.of(properties.documentsDir()).toAbsolutePath().normalize();
    }

    public String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required JVM algorithm; its absence is unrecoverable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // Persists the bytes and returns the relative storage path to record on the entity.
    // Content-addressed, so re-writing the same file just overwrites identical bytes.
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

    // Resolves a stored relative path back to an absolute path (used by later extraction).
    public Path resolve(String relativePath) {
        return root.resolve(relativePath);
    }

    // Reads previously-stored bytes back. A missing/unreadable file throws, which the
    // ingestion pipeline turns into a FAILED document rather than a crash.
    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(root.resolve(relativePath));
        } catch (IOException e) {
            throw new DocumentStorageException("Could not read stored document bytes.", e);
        }
    }
}
