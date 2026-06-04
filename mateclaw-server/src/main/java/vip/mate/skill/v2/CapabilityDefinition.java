package vip.mate.skill.v2;

import java.util.List;
import java.util.Map;

public record CapabilityDefinition(
        String id,
        String title,
        String description,
        List<String> inputSchemas,
        List<String> outputSchemas,
        List<String> tools,
        Map<String, Object> metadata
) {

    public CapabilityDefinition {
        inputSchemas = inputSchemas == null ? List.of() : List.copyOf(inputSchemas);
        outputSchemas = outputSchemas == null ? List.of() : List.copyOf(outputSchemas);
        tools = tools == null ? List.of() : List.copyOf(tools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
