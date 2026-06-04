package vip.mate.skill.v2;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryAdapterRegistry implements AdapterRegistry {

    private final Map<String, Map<String, AdapterDefinition>> adaptersBySkill = new ConcurrentHashMap<>();

    @Override
    public void register(SkillPackageV2 skillPackage) {
        Map<String, AdapterDefinition> byId = new LinkedHashMap<>();
        for (AdapterDefinition adapter : skillPackage.manifest().adapters()) {
            byId.put(adapter.id(), adapter);
        }
        adaptersBySkill.put(skillPackage.id(), Map.copyOf(byId));
    }

    @Override
    public Optional<AdapterDefinition> find(String skillId, String adapterId) {
        return Optional.ofNullable(adaptersBySkill.getOrDefault(skillId, Map.of()).get(adapterId));
    }

    @Override
    public List<AdapterDefinition> list(String skillId) {
        return List.copyOf(adaptersBySkill.getOrDefault(skillId, Map.of()).values());
    }
}
