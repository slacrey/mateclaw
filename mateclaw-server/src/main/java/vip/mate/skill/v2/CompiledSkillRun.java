package vip.mate.skill.v2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record CompiledSkillRun(
        String skillId,
        String version,
        JsonNode workflow,
        List<CapabilityDefinition> capabilities,
        List<AdapterDefinition> adapters,
        Map<String, String> schemas,
        Map<String, String> prompts,
        Map<String, String> templates,
        Map<String, String> evals
) {

    public CompiledSkillRun {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        adapters = adapters == null ? List.of() : List.copyOf(adapters);
        schemas = schemas == null ? Map.of() : Map.copyOf(schemas);
        prompts = prompts == null ? Map.of() : Map.copyOf(prompts);
        templates = templates == null ? Map.of() : Map.copyOf(templates);
        evals = evals == null ? Map.of() : Map.copyOf(evals);
    }
}
