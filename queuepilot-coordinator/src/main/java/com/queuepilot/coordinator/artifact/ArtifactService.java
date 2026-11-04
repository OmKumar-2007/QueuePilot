package com.queuepilot.coordinator.artifact;

import com.queuepilot.coordinator.error.Errors.NotFoundException;
import com.queuepilot.coordinator.error.Errors.ValidationException;
import com.queuepilot.core.schema.ValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    /** Largest file accepted (50 MB). */
    static final long MAX_ARTIFACT_BYTES = 50L * 1024 * 1024;

    private final ArtifactRepository repository;
    private final ArtifactStorage storage;
    private final Clock clock;

    public ArtifactService(ArtifactRepository repository, ArtifactStorage storage, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public ArtifactEntity uploadInput(MultipartFile file) {
        return save(file, null);
    }

    @Transactional
    public ArtifactEntity uploadOutput(MultipartFile file, String jobId) {
        return save(file, jobId);
    }

    private ArtifactEntity save(MultipartFile file, String jobId) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("file", "is required and must not be empty");
        }
        String name = sanitizeName(file.getOriginalFilename());
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        try (InputStream in = file.getInputStream()) {
            String id = UUID.randomUUID().toString();
            ArtifactStorage.StoredFile stored = storage.store(id, in, MAX_ARTIFACT_BYTES);
            Instant now = clock.instant();
            return repository.save(new ArtifactEntity(id, jobId, name,
                    contentType.length() > 100 ? "application/octet-stream" : contentType, stored, now));
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
            return storage.open(artifact.getId());
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
            if (artifact == null) {
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

    /** Best effort: a file that cannot be deleted is only wasted disk space, so it must not fail the caller. */
    public void deleteFiles(List<String> ids) {
        for (String id : ids) {
            try {
                storage.delete(id);
            } catch (IOException e) {
                log.warn("Could not delete artifact file {}: {}", id, e.getMessage());
            }
        }
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
