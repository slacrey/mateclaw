package vip.mate.os.run;

import org.junit.jupiter.api.Test;
import vip.mate.os.run.model.AgentRunStatus;
import vip.mate.os.run.runtime.AgentRunStateMachine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunStateMachineTest {

    private final AgentRunStateMachine stateMachine = new AgentRunStateMachine();

    @Test
    void allowsCreatedToRunningAndRunningToSucceeded() {
        stateMachine.assertTransition(AgentRunStatus.CREATED, AgentRunStatus.RUNNING);
        stateMachine.assertTransition(AgentRunStatus.RUNNING, AgentRunStatus.SUCCEEDED);
    }

    @Test
    void rejectsTerminalToRunning() {
        assertThatThrownBy(() -> stateMachine.assertTransition(AgentRunStatus.SUCCEEDED, AgentRunStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("illegal run status transition");
    }
}
