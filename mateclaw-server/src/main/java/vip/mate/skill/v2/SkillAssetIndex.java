package vip.mate.skill.v2;

import java.util.List;

public record SkillAssetIndex(
        List<String> schemas,
        List<String> prompts,
        List<String> templates,
        List<String> evals
) {

    public SkillAssetIndex {
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
        prompts = prompts == null ? List.of() : List.copyOf(prompts);
        templates = templates == null ? List.of() : List.copyOf(templates);
        evals = evals == null ? List.of() : List.copyOf(evals);
    }

    public static SkillAssetIndex empty() {
        return new SkillAssetIndex(List.of(), List.of(), List.of(), List.of());
    }
}
