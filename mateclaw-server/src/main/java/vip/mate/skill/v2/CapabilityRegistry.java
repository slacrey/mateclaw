package vip.mate.skill.v2;

import java.util.List;
import java.util.Optional;

public interface CapabilityRegistry {

    void register(SkillPackageV2 skillPackage);

    Optional<CapabilityDefinition> find(String skillId, String capabilityId);

    List<CapabilityDefinition> list(String skillId);
}
