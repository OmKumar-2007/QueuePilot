package com.queuepilot.coordinator.artifact;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Metadata of an uploaded input file or a task-produced output file. The bytes are stored under the artifact id. */
@Entity
@Table(name = "artifact")
public class ArtifactEntity {

    @Id
    private String id;
    private String jobId;
    private String originalName;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private Instant createdAt;

    protected ArtifactEntity() {
    }

    public ArtifactEntity(String id, String jobId, String originalName, String contentType,
                          ArtifactStorage.StoredFile stored, Instant createdAt) {
        this.id = id;
        this.jobId = jobId;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = stored.sizeBytes();
        this.sha256 = stored.sha256();
        this.createdAt = createdAt;
    }

    public void linkTo(String jobId) {
        this.jobId = jobId;
    }

    public String getId() { return id; }
    public String getJobId() { return jobId; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public Instant getCreatedAt() { return createdAt; }
}
