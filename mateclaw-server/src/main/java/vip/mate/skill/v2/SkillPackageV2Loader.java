package vip.mate.skill.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads a Skill Package v2 bundle without touching the legacy SKILL.md runtime.
 */
@Component
public class SkillPackageV2Loader {

    private final ObjectMapper objectMapper;
    private final Yaml yaml = new Yaml();
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    public SkillPackageV2Loader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SkillPackageV2 load(Path packageDirectory) {
        Objects.requireNonNull(packageDirectory, "packageDirectory must not be null");
        Path root = packageDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new SkillPackageV2Exception("Skill package directory does not exist: " + root);
        }

        SkillV2Manifest manifest = readManifest(root.resolve("skill.yaml"));
        JsonNode workflow = readWorkflow(root.resolve("workflow.json"));
        return new SkillPackageV2(
                manifest,
                workflow,
                readAssetDirectory(root, "adapters"),
                readAssetDirectory(root, "schemas"),
                readAssetDirectory(root, "prompts"),
                readAssetDirectory(root, "templates"),
                readAssetDirectory(root, "evals"),
                root);
    }

    public SkillPackageV2 loadBundled(String packageName) {
        String base = "skills/" + packageName + "/";
        try {
            Resource manifestResource = resourceResolver.getResource("classpath:" + base + "skill.yaml");
            if (!manifestResource.exists()) {
                throw new SkillPackageV2Exception("Bundled Skill Package v2 not found: " + packageName);
            }
            SkillV2Manifest manifest;
            try (InputStream in = manifestResource.getInputStream()) {
                manifest = parseManifest(in);
            }
            JsonNode workflow;
            try (InputStream in = resourceResolver.getResource("classpath:" + base + "workflow.json").getInputStream()) {
                workflow = objectMapper.readTree(in);
            }
            return new SkillPackageV2(
                    manifest,
                    workflow,
                    readClasspathAssets(base, "adapters"),
                    readClasspathAssets(base, "schemas"),
                    readClasspathAssets(base, "prompts"),
                    readClasspathAssets(base, "templates"),
                    readClasspathAssets(base, "evals"),
                    null);
        } catch (IOException ex) {
            throw new SkillPackageV2Exception("Failed to load bundled Skill Package v2: " + packageName, ex);
        }
    }

    private SkillV2Manifest readManifest(Path path) {
        requireRegularFile(path, "skill.yaml");
        try (InputStream in = Files.newInputStream(path)) {
            return parseManifest(in);
        } catch (IOException ex) {
            throw new SkillPackageV2Exception("Failed to read skill.yaml: " + path, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private SkillV2Manifest parseManifest(InputStream in) {
        Object loaded = yaml.load(in);
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw new SkillPackageV2Exception("skill.yaml must contain a mapping");
        }
        Map<String, Object> map = toStringObjectMap(raw);
        SkillV2Manifest manifest = new SkillV2Manifest(
                string(map, "id"),
                string(map, "version"),
                string(map, "package_version"),
                string(map, "name"),
                string(map, "description"),
                string(map, "category"),
                stringList(map.get("tags")),
                parseCapabilities(map.get("capabilities")),
                parseAdapters(map.get("adapters")),
                parseAssets(map.get("assets")),
                map.get("config") instanceof Map<?, ?> config ? toStringObjectMap(config) : Map.of());
        validateManifest(manifest);
        return manifest;
    }

    private JsonNode readWorkflow(Path path) {
        requireRegularFile(path, "workflow.json");
        try (InputStream in = Files.newInputStream(path)) {
            return objectMapper.readTree(in);
        } catch (IOException ex) {
            throw new SkillPackageV2Exception("Failed to read workflow.json: " + path, ex);
        }
    }

    private Map<String, String> readAssetDirectory(Path root, String child) {
        Path directory = root.resolve(child).normalize();
        if (!directory.startsWith(root)) {
            throw new SkillPackageV2Exception("Asset directory escapes package root: " + child);
        }
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> out.put(root.relativize(path).toString().replace('\\', '/'), readString(path)));
        } catch (IOException ex) {
            throw new SkillPackageV2Exception("Failed to read asset directory: " + directory, ex);
        }
        return out;
    }

    private Map<String, String> readClasspathAssets(String base, String child) throws IOException {
        String pattern = "classpath*:" + base + child + "/**/*";
        Map<String, String> out = new LinkedHashMap<>();
        for (Resource resource : resourceResolver.getResources(pattern)) {
            if (!resource.isReadable() || resource.getFilename() == null) {
                continue;
            }
            String path = classpathRelativePath(resource, base, child);
            if (path != null) {
                out.put(path, resource.getContentAsString(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private String classpathRelativePath(Resource resource, String base, String child) throws IOException {
        URI uri = resource.getURI();
        String text = uri.toString().replace('\\', '/');
        String marker = "/" + base + child + "/";
        int index = text.indexOf(marker);
        if (index < 0) {
            return null;
        }
        return child + "/" + text.substring(index + marker.length());
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new SkillPackageV2Exception("Failed to read asset: " + path, ex);
        }
    }

    private static void requireRegularFile(Path path, String name) {
        if (!Files.isRegularFile(path)) {
            throw new SkillPackageV2Exception("Missing required " + name + ": " + path);
        }
    }

    private static void validateManifest(SkillV2Manifest manifest) {
        if (!manifest.isV2()) {
            throw new SkillPackageV2Exception("skill.yaml package_version must be v2");
        }
        if (isBlank(manifest.id())) {
            throw new SkillPackageV2Exception("skill.yaml id is required");
        }
        if (manifest.capabilities().isEmpty()) {
            throw new SkillPackageV2Exception("skill.yaml must declare at least one capability");
        }
        for (CapabilityDefinition capability : manifest.capabilities()) {
            if (isBlank(capability.id())) {
                throw new SkillPackageV2Exception("capability id is required");
            }
        }
        for (AdapterDefinition adapter : manifest.adapters()) {
            if (isBlank(adapter.id()) || isBlank(adapter.kind())) {
                throw new SkillPackageV2Exception("adapter id and kind are required");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CapabilityDefinition> parseCapabilities(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<CapabilityDefinition> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> m = toStringObjectMap(map);
            out.add(new CapabilityDefinition(
                    string(m, "id"),
                    string(m, "title"),
                    string(m, "description"),
                    stringList(m.get("input_schemas")),
                    stringList(m.get("output_schemas")),
                    stringList(m.get("tools")),
                    m.get("metadata") instanceof Map<?, ?> metadata ? toStringObjectMap(metadata) : Map.of()));
        }
        return out;
    }

    private static List<AdapterDefinition> parseAdapters(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<AdapterDefinition> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> m = toStringObjectMap(map);
            out.add(new AdapterDefinition(
                    string(m, "id"),
                    string(m, "kind"),
                    string(m, "path"),
                    stringList(m.get("capabilities")),
                    m.get("config") instanceof Map<?, ?> config ? toStringObjectMap(config) : Map.of()));
        }
        return out;
    }

    private static SkillAssetIndex parseAssets(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return SkillAssetIndex.empty();
        }
        Map<String, Object> m = toStringObjectMap(map);
        return new SkillAssetIndex(
                stringList(m.get("schemas")),
                stringList(m.get("prompts")),
                stringList(m.get("templates")),
                stringList(m.get("evals")));
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(item.toString());
            }
        }
        return out;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
