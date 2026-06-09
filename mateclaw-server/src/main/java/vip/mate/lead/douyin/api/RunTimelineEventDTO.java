package vip.mate.lead.douyin.api;

import vip.mate.os.run.model.AgentEventEntity;

public record RunTimelineEventDTO(
        String id,
        String stepId,
        String type,
        String severity,
        String payloadJson
) {
    public static RunTimelineEventDTO from(AgentEventEntity row) {
        return new RunTimelineEventDTO(
                DouyinLeadAcquisitionRunResponse.id(row.getId()),
                DouyinLeadAcquisitionRunResponse.id(row.getStepId()),
                row.getEventType(),
                row.getSeverity(),
                row.getPayloadJson());
    }
}
