package vip.mate.os.run.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum AgentRunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_HUMAN,
    PAUSED,
    CANCELLING,
    FAILED,
    SUCCEEDED,
    ABORTED;

    private static final Set<AgentRunStatus> TERMINAL =
            EnumSet.of(FAILED, SUCCEEDED, ABORTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AgentRunStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("run status is required");
        }
        return AgentRunStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
