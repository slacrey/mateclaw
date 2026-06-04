package vip.mate.workflow.v2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum WorkflowV2StepType {
    AGENT_ASK("agent.ask"),
    SKILL_INVOKE("skill.invoke"),
    BROWSER_OBSERVE("browser.observe"),
    BROWSER_ACT("browser.act"),
    BROWSER_EXTRACT("browser.extract"),
    API_CALL("api.call"),
    MCP_CALL("mcp.call"),
    LLM_CLASSIFY_BATCH("llm.classify.batch"),
    LOOP("loop"),
    LOOP_ITEMS("loop.items"),
    FAN_OUT("fan_out"),
    COLLECT("collect"),
    CONDITIONAL("conditional"),
    AWAIT_APPROVAL("await_approval"),
    HUMAN_TAKEOVER("human.takeover"),
    CHECKPOINT("checkpoint"),
    WRITE_MEMORY("write_memory"),
    EMIT_METRIC("emit_metric");

    private final String wire;

    WorkflowV2StepType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static WorkflowV2StepType fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workflow v2 step type is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (WorkflowV2StepType type : values()) {
            if (type.wire.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown workflow v2 step type: " + value);
    }
}
