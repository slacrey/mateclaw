package vip.mate.os.run.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum AgentStepStatus {
    OPEN,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_HUMAN,
    PAUSED,
    CANCELLED,
    FAILED,
    SUCCEEDED,
    SKIPPED;

    private static final Set<AgentStepStatus> TERMINAL =
            EnumSet.of(CANCELLED, FAILED, SUCCEEDED, SKIPPED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
