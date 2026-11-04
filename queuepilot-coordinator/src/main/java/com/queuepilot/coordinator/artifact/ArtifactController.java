package com.queuepilot.coordinator.artifact;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@RestController
public class ArtifactController {

    private final ArtifactService artifacts;

    public ArtifactController(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @PostMapping(path = "/api/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ArtifactView upload(@RequestParam("file") MultipartFile file) {
        return ArtifactView.from(artifacts.uploadInput(file));
    }

    @GetMapping("/api/artifacts/{id}")
    public ArtifactView metadata(@PathVariable String id) {
        return ArtifactView.from(artifacts.get(id));
    }

    @GetMapping("/api/artifacts/{id}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id) {
        ArtifactEntity artifact = artifacts.get(id);
        return fileResponse(artifact, artifacts);
    }

    static ResponseEntity<InputStreamResource> fileResponse(ArtifactEntity artifact, ArtifactService artifacts) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.getContentType()))
                .contentLength(artifact.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.getOriginalName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(artifacts.open(artifact)));
    }

    public record ArtifactView(String id, String jobId, String originalName, String contentType,
                               long sizeBytes, String sha256, Instant createdAt) {
        public static ArtifactView from(ArtifactEntity a) {
            return new ArtifactView(a.getId(), a.getJobId(), a.getOriginalName(), a.getContentType(),
                    a.getSizeBytes(), a.getSha256(), a.getCreatedAt());
        }
    }
}
