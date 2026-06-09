package vip.mate.os.run.runtime;

import org.springframework.stereotype.Service;
import vip.mate.os.run.model.AgentArtifactEntity;
import vip.mate.os.run.repository.AgentArtifactMapper;

@Service
public class ArtifactService {

    private final AgentArtifactMapper artifactMapper;
    private final RunEventPublisher events;

    public ArtifactService(AgentArtifactMapper artifactMapper, RunEventPublisher events) {
        this.artifactMapper = artifactMapper;
        this.events = events;
    }

    public AgentArtifactEntity record(ArtifactRecord record) {
        AgentArtifactEntity row = new AgentArtifactEntity();
        row.setRunId(record.runId());
        row.setStepId(record.stepId());
        row.setWorkspaceId(record.workspaceId());
        row.setArtifactUri(record.artifactUri());
        row.setArtifactKind(record.artifactKind());
        row.setContentType(record.contentType());
        row.setStorageKind(record.storageKind());
        row.setStorageRef(record.storageRef());
        row.setSha256(record.sha256());
        row.setSizeBytes(record.sizeBytes());
        row.setMetadataJson(record.metadataJson());
        artifactMapper.insert(row);
        if (record.runId() != null) {
            events.publish(new RunEvent(record.runId(), record.stepId(), "artifact_created", "info",
                    "{\"artifactUri\":\"" + record.artifactUri() + "\",\"kind\":\""
                            + record.artifactKind() + "\"}",
                    String.valueOf(row.getId())));
        }
        return row;
    }

    public record ArtifactRecord(
            Long runId,
            Long stepId,
            Long workspaceId,
            String artifactUri,
            String artifactKind,
            String contentType,
            String storageKind,
            String storageRef,
            String sha256,
            Long sizeBytes,
            String metadataJson
    ) {
        public ArtifactRecord {
            if (workspaceId == null) {
                throw new IllegalArgumentException("workspaceId is required");
            }
            if (artifactUri == null || artifactUri.isBlank()) {
                throw new IllegalArgumentException("artifactUri is required");
            }
            if (artifactKind == null || artifactKind.isBlank()) {
                throw new IllegalArgumentException("artifactKind is required");
            }
            if (storageKind == null || storageKind.isBlank()) {
                throw new IllegalArgumentException("storageKind is required");
            }
        }
    }
}
