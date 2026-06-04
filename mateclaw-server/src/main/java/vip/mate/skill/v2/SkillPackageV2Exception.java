package vip.mate.skill.v2;

/**
 * Raised when a Skill Package v2 bundle cannot be loaded or compiled.
 */
public class SkillPackageV2Exception extends RuntimeException {

    public SkillPackageV2Exception(String message) {
        super(message);
    }

    public SkillPackageV2Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
