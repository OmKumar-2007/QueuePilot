package com.queuepilot.coordinator.artifact;

import com.queuepilot.coordinator.config.QueuePilotProperties;
import com.queuepilot.coordinator.error.Errors.NotFoundException;
import com.queuepilot.coordinator.error.Errors.ValidationException;
import com.queuepilot.core.schema.ValidationError;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Upload, download and job-linking rules for artifacts (F21). */
@Service
public class ArtifactService {

    private final ArtifactRepository repository;
    private final ArtifactStorage storage;
    private final QueuePilotProperties properties;
    private final Clock clock;

    public ArtifactService(ArtifactRepository repository, ArtifactStorage storage, QueuePilotProperties properties, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ArtifactEntity uploadInput(MultipartFile file, String clientId) {
        return save(file, null, ArtifactEntity.ROLE_INPUT, clientId);
    }

    @Transactional
    public ArtifactEntity uploadOutput(MultipartFile file, String jobId) {
        return save(file, jobId, ArtifactEntity.ROLE_OUTPUT, null);
    }

    private ArtifactEntity save(MultipartFile file, String jobId, String role, String clientId) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("file", "is required and must not be empty");
        }
        String name = sanitizeName(file.getOriginalFilename());
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        try (InputStream in = file.getInputStream()) {
            ArtifactStorage.StoredFile stored = storage.store(in, properties.maxArtifactBytes());
            Instant now = clock.instant();
            return repository.save(new ArtifactEntity(UUID.randomUUID().toString(), jobId, role, name,
                    contentType.length() > 100 ? "application/octet-stream" : contentType, stored, clientId, now));
        } catch (ArtifactStorage.TooLargeException e) {
            throw new ValidationException("file", e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store artifact", e);
        }
    }

    @Transactional(readOnly = true)
    public ArtifactEntity get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("No artifact " + id));
    }

    public InputStream open(ArtifactEntity artifact) {
        try {
            return storage.open(artifact.getStorageKey());
        } catch (IOException e) {
            throw new NotFoundException("Artifact content is missing");
        }
    }

    /** Checks that input artifacts referenced by a new job exist and are not used by another job. */
    @Transactional(readOnly = true)
    public List<ValidationError> checkLinkable(List<String> ids) {
        List<ValidationError> errors = new ArrayList<>();
        for (String id : ids) {
            ArtifactEntity artifact = repository.findById(id).orElse(null);
            if (artifact == null || !ArtifactEntity.ROLE_INPUT.equals(artifact.getRole())) {
                errors.add(new ValidationError("input", "artifact " + id + " does not exist"));
            } else if (artifact.getJobId() != null) {
                errors.add(new ValidationError("input", "artifact " + id + " is already used by job " + artifact.getJobId()));
            }
        }
        return errors;
    }

    @Transactional
    public void link(List<String> ids, String jobId) {
        for (String id : ids) {
            get(id).linkTo(jobId);
        }
    }

    @Transactional(readOnly = true)
    public List<ArtifactEntity> forJob(String jobId) {
        return repository.findByJobId(jobId);
    }

    @Transactional(readOnly = true)
    public List<String> inputIdsForJob(String jobId) {
        return repository.findByJobIdAndRole(jobId, ArtifactEntity.ROLE_INPUT).stream().map(ArtifactEntity::getId).toList();
    }

    private static String sanitizeName(String original) {
        if (original == null || original.isBlank()) {
            return "upload.bin";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
