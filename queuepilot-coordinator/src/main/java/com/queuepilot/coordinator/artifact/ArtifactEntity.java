package com.queuepilot.coordinator.artifact;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Metadata of an uploaded input file or a task-produced output file. */
@Entity
@Table(name = "artifact")
public class ArtifactEntity {

    public static final String ROLE_INPUT = "INPUT";
    public static final String ROLE_OUTPUT = "OUTPUT";

    @Id
    private String id;
    private String jobId;
    private String role;
    private String originalName;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private String storageKey;
    private String clientId;
    private Instant createdAt;

    protected ArtifactEntity() {
    }

    public ArtifactEntity(String id, String jobId, String role, String originalName, String contentType,
                          ArtifactStorage.StoredFile stored, String clientId, Instant createdAt) {
        this.id = id;
        this.jobId = jobId;
        this.role = role;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = stored.sizeBytes();
        this.sha256 = stored.sha256();
        this.storageKey = stored.storageKey();
        this.clientId = clientId;
        this.createdAt = createdAt;
    }

    public void linkTo(String jobId) {
        this.jobId = jobId;
    }

    public String getId() { return id; }
    public String getJobId() { return jobId; }
    public String getRole() { return role; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getStorageKey() { return storageKey; }
    public String getClientId() { return clientId; }
    public Instant getCreatedAt() { return createdAt; }
}
