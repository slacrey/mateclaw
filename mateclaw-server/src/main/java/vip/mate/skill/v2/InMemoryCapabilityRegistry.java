package vip.mate.skill.v2;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryCapabilityRegistry implements CapabilityRegistry {

    private final Map<String, Map<String, CapabilityDefinition>> capabilitiesBySkill = new ConcurrentHashMap<>();

    @Override
    public void register(SkillPackageV2 skillPackage) {
        Map<String, CapabilityDefinition> byId = new LinkedHashMap<>();
        for (CapabilityDefinition capability : skillPackage.manifest().capabilities()) {
            byId.put(capability.id(), capability);
        }
        capabilitiesBySkill.put(skillPackage.id(), Map.copyOf(byId));
    }

    @Override
    public Optional<CapabilityDefinition> find(String skillId, String capabilityId) {
        return Optional.ofNullable(capabilitiesBySkill.getOrDefault(skillId, Map.of()).get(capabilityId));
    }

    @Override
    public List<CapabilityDefinition> list(String skillId) {
        return List.copyOf(capabilitiesBySkill.getOrDefault(skillId, Map.of()).values());
    }
}
