package vip.mate.os.run.runtime;

public interface PolicyEngine {

    PolicyDecision evaluate(AgentStepRequest step);
}
