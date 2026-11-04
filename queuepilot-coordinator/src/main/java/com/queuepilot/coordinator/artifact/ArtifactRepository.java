package com.queuepilot.coordinator.artifact;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, String> {

    List<ArtifactEntity> findByJobId(String jobId);
}
