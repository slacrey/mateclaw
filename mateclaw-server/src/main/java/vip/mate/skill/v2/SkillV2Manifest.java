package vip.mate.skill.v2;

import java.util.List;
import java.util.Map;

/**
 * Typed view of skill.yaml for Skill Package v2.
 */
public record SkillV2Manifest(
        String id,
        String version,
        String packageVersion,
        String name,
        String description,
        String category,
        List<String> tags,
        List<CapabilityDefinition> capabilities,
        List<AdapterDefinition> adapters,
        SkillAssetIndex assets,
        Map<String, Object> config
) {

    public SkillV2Manifest {
        tags = tags == null ? List.of() : List.copyOf(tags);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        adapters = adapters == null ? List.of() : List.copyOf(adapters);
        assets = assets == null ? SkillAssetIndex.empty() : assets;
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public boolean isV2() {
        return "v2".equalsIgnoreCase(packageVersion);
    }
}
