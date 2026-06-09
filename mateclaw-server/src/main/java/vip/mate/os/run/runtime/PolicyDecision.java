package vip.mate.os.run.runtime;

public record PolicyDecision(
        Decision decision,
        String reason,
        String policyTags
) {
    public enum Decision {
        ALLOW,
        REQUIRE_APPROVAL,
        DENY
    }

    public static PolicyDecision allow(String tags) {
        return new PolicyDecision(Decision.ALLOW, "allowed", tags);
    }

    public static PolicyDecision requireApproval(String tags) {
        return new PolicyDecision(Decision.REQUIRE_APPROVAL, "approval required by policy tag", tags);
    }
}
