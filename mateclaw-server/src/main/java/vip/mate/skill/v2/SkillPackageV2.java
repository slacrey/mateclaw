package vip.mate.skill.v2;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Map;

public record SkillPackageV2(
        SkillV2Manifest manifest,
        JsonNode workflow,
        Map<String, String> adapters,
        Map<String, String> schemas,
        Map<String, String> prompts,
        Map<String, String> templates,
        Map<String, String> evals,
        Path sourceDirectory
) {

    public SkillPackageV2 {
        adapters = adapters == null ? Map.of() : Map.copyOf(adapters);
        schemas = schemas == null ? Map.of() : Map.copyOf(schemas);
        prompts = prompts == null ? Map.of() : Map.copyOf(prompts);
        templates = templates == null ? Map.of() : Map.copyOf(templates);
        evals = evals == null ? Map.of() : Map.copyOf(evals);
    }

    public String id() {
        return manifest == null ? null : manifest.id();
    }
}
