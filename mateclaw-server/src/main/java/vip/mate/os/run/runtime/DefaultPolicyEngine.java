package vip.mate.os.run.runtime;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class DefaultPolicyEngine implements PolicyEngine {

    @Override
    public PolicyDecision evaluate(AgentStepRequest step) {
        String tags = step.policyTags();
        if (tags == null || tags.isBlank()) {
            return PolicyDecision.allow(tags);
        }
        String normalized = tags.toLowerCase(Locale.ROOT);
        if (normalized.contains("approval_required") || normalized.contains("sensitive")) {
            return PolicyDecision.requireApproval(tags);
        }
        return PolicyDecision.allow(tags);
    }
}
