package com.studyloop.backend.document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

// Where uploaded document bytes live, behind an interface because the answer differs by
// deployment rather than by code path. Two implementations: the local filesystem (dev, and any
// host with a durable disk) and Supabase Storage (a free-tier container, whose filesystem does
// not survive a restart). StorageConfig picks one from studyloop.storage.provider.
//
// The contract is content-addressed and relative: bytes are stored under "{courseId}/{sha256}"
// and that relative path is what the entity keeps, so the root can move between environments —
// or stop being a filesystem root at all — without rewriting a single row.
public interface DocumentStorageService {

    // Pure, and identical whatever backs the store, so it is defaulted here rather than
    // reimplemented — and duplicated — per provider.
    default String sha256Hex(byte[] bytes) {
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
    String store(UUID courseId, String sha256, byte[] bytes);

    // Reads previously-stored bytes back. A missing or unreadable object throws, which the
    // ingestion pipeline turns into a FAILED document rather than a crash.
    byte[] read(String relativePath);
}
