package vip.mate.skill.v2;

import java.util.List;
import java.util.Map;

public record AdapterDefinition(
        String id,
        String kind,
        String path,
        List<String> capabilities,
        Map<String, Object> config
) {

    public AdapterDefinition {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
