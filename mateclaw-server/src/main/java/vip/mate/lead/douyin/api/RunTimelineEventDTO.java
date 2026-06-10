package vip.mate.lead.douyin.api;

import vip.mate.os.run.model.AgentEventEntity;

public record RunTimelineEventDTO(
        String id,
        String runId,
        String stepId,
        String type,
        String severity,
        String payloadJson,
        String createTime
) {
    public RunTimelineEventDTO(String id,
                               String stepId,
                               String type,
                               String severity,
                               String payloadJson) {
        this(id, null, stepId, type, severity, payloadJson, null);
    }

    public static RunTimelineEventDTO from(AgentEventEntity row) {
        return new RunTimelineEventDTO(
                DouyinLeadAcquisitionRunResponse.id(row.getId()),
                DouyinLeadAcquisitionRunResponse.id(row.getRunId()),
                DouyinLeadAcquisitionRunResponse.id(row.getStepId()),
                row.getEventType(),
                row.getSeverity(),
                row.getPayloadJson(),
                row.getCreateTime() == null ? null : row.getCreateTime().toString());
    }
}
