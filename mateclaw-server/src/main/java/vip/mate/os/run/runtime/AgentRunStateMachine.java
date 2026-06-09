package vip.mate.os.run.runtime;

import org.springframework.stereotype.Component;
import vip.mate.os.run.model.AgentRunStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

@Component
public class AgentRunStateMachine {

    private final EnumMap<AgentRunStatus, Set<AgentRunStatus>> transitions =
            new EnumMap<>(AgentRunStatus.class);

    public AgentRunStateMachine() {
        allow(AgentRunStatus.CREATED, AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.ABORTED);
        allow(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.CANCELLING, AgentRunStatus.ABORTED);
        allow(AgentRunStatus.RUNNING,
                AgentRunStatus.WAITING_APPROVAL,
                AgentRunStatus.WAITING_HUMAN,
                AgentRunStatus.PAUSED,
                AgentRunStatus.CANCELLING,
                AgentRunStatus.FAILED,
                AgentRunStatus.SUCCEEDED,
                AgentRunStatus.ABORTED);
        allow(AgentRunStatus.WAITING_APPROVAL, AgentRunStatus.RUNNING, AgentRunStatus.CANCELLING, AgentRunStatus.FAILED, AgentRunStatus.ABORTED);
        allow(AgentRunStatus.WAITING_HUMAN, AgentRunStatus.RUNNING, AgentRunStatus.CANCELLING, AgentRunStatus.FAILED, AgentRunStatus.ABORTED);
        allow(AgentRunStatus.PAUSED, AgentRunStatus.RUNNING, AgentRunStatus.CANCELLING, AgentRunStatus.FAILED, AgentRunStatus.ABORTED);
        allow(AgentRunStatus.CANCELLING, AgentRunStatus.ABORTED, AgentRunStatus.FAILED);
        allow(AgentRunStatus.FAILED);
        allow(AgentRunStatus.SUCCEEDED);
        allow(AgentRunStatus.ABORTED);
    }

    public void assertTransition(AgentRunStatus from, AgentRunStatus to) {
        if (from == to) {
            return;
        }
        if (!transitions.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("illegal run status transition: " + from + " -> " + to);
        }
    }

    private void allow(AgentRunStatus from, AgentRunStatus... to) {
        transitions.put(from, to.length == 0 ? EnumSet.noneOf(AgentRunStatus.class) : EnumSet.of(to[0], to));
    }
}
