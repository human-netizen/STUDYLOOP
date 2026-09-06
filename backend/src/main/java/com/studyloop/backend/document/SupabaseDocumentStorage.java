package com.studyloop.backend.document;

import com.studyloop.backend.config.StorageProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

// Writes uploaded document bytes to Supabase Storage over its REST API.
//
// **Why this exists.** No free hosting tier offers a persistent disk, so the container filesystem
// is rebuilt on every deploy and every wake from idle. Chunks, vectors and every derived artifact
// live in Postgres and are untouched by that — search, chat, quizzes and flashcards keep working
// across a restart. The single casualty is DocumentService.getContent, which serves the source
// behind a citation and is the only reader of the stored bytes. Object storage moves those bytes
// off the ephemeral disk and onto the same Supabase project that already holds the database.
//
// **Why the service-role key, and why that is safe here.** The bucket is private and every call
// carries the service-role key, which bypasses row-level security. That is correct in this one
// place because the key never leaves the server and the access decision is already made upstream:
// getContent calls courseAccess.requireMember before it asks for a byte, and store() is reached
// only through an authenticated upload. A per-user token would re-answer a question this
// application answers in its own service layer.
//
// **Paths are unchanged.** The object key is the same "{courseId}/{sha256}" the filesystem
// implementation writes, so the two are interchangeable, storage_path means one thing under
// either, and a deployment can move between them without a migration.
public class SupabaseDocumentStorage implements DocumentStorageService {

    private final RestClient restClient = RestClient.create();
    private final String objectBaseUrl;
    private final String serviceKey;

    public SupabaseDocumentStorage(StorageProperties.Supabase properties) {
        // Tolerate a trailing slash on the project URL: it is copied out of a dashboard by hand,
        // and "https://x.supabase.co//storage/v1/..." is a 404 that reads like a missing file.
        String base = properties.url().replaceAll("/+$", "");
        this.objectBaseUrl = base + "/storage/v1/object/" + properties.bucket() + "/";
        this.serviceKey = properties.serviceKey();
    }

    @Override
    public String store(UUID courseId, String sha256, byte[] bytes) {
        String relativePath = courseId + "/" + sha256;
        try {
            restClient.post()
                    .uri(objectBaseUrl + relativePath)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("apikey", serviceKey)
                    // Content is addressed by hash, so an upload of the same file is the same
                    // bytes at the same key. Without upsert that repeat is a 409, which would
                    // turn re-uploading an identical document into a failure for no reason.
                    .header("x-upsert", "true")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new DocumentStorageException("Could not store the uploaded file.", e);
        }
        return relativePath;
    }

    @Override
    public byte[] read(String relativePath) {
        try {
            byte[] bytes = restClient.get()
                    .uri(objectBaseUrl + relativePath)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("apikey", serviceKey)
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null) {
                throw new DocumentStorageException("Stored document bytes were empty.", null);
            }
            return bytes;
        } catch (RestClientException e) {
            throw new DocumentStorageException("Could not read stored document bytes.", e);
        }
    }
}
