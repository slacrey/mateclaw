package vip.mate.skill.v2;

import java.util.List;
import java.util.Optional;

public interface AdapterRegistry {

    void register(SkillPackageV2 skillPackage);

    Optional<AdapterDefinition> find(String skillId, String adapterId);

    List<AdapterDefinition> list(String skillId);
}
