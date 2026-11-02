package com.queuepilot.coordinator.artifact;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where artifact bytes live. Metadata stays in the database ({@link ArtifactEntity}); only this
 * interface knows the physical location, so the local folder used in v1.0 could later be replaced
 * by object storage without touching the services.
 */
public interface ArtifactStorage {

    /** Stores the stream under a new generated key, rejecting anything larger than {@code maxBytes}. */
    StoredFile store(InputStream content, long maxBytes) throws IOException;

    InputStream open(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;

    record StoredFile(String storageKey, long sizeBytes, String sha256) {
    }

    class TooLargeException extends IOException {
        public TooLargeException(long maxBytes) {
            super("File exceeds the limit of " + maxBytes + " bytes");
        }
    }
}
