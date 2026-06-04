package vip.mate.os.run.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import vip.mate.os.run.model.AgentEventEntity;
import vip.mate.os.run.repository.AgentEventMapper;

@Service
public class RunEventPublisher {

    private final AgentEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public RunEventPublisher(AgentEventMapper eventMapper, ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    public AgentEventEntity publish(RunEvent event) {
        AgentEventEntity row = new AgentEventEntity();
        row.setRunId(event.runId());
        row.setStepId(event.stepId());
        row.setEventType(event.eventType());
        row.setSeverity(event.severity());
        row.setPayloadJson(toJson(event.payload()));
        row.setArtifactIds(event.artifactIds());
        eventMapper.insert(row);
        return row;
    }

    private String toJson(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"serialization_error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
