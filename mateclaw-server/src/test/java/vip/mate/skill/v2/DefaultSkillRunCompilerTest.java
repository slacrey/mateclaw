package vip.mate.skill.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSkillRunCompilerTest {

    private final InMemoryCapabilityRegistry capabilityRegistry = new InMemoryCapabilityRegistry();
    private final InMemoryAdapterRegistry adapterRegistry = new InMemoryAdapterRegistry();
    private final DefaultSkillRunCompiler compiler = new DefaultSkillRunCompiler(capabilityRegistry, adapterRegistry);

    @Test
    void compilesPackageAndRegistersCapabilitiesAndAdapters() {
        SkillPackageV2 skillPackage = new SkillPackageV2Loader(new ObjectMapper())
                .loadBundled("douyin-lead-acquisition");

        CompiledSkillRun run = compiler.compile(skillPackage);

        assertThat(run.skillId()).isEqualTo("douyin.lead_acquisition");
        assertThat(run.capabilities()).hasSize(3);
        assertThat(run.adapters()).hasSize(2);
        assertThat(capabilityRegistry.find("douyin.lead_acquisition", "discover_leads")).isPresent();
        assertThat(adapterRegistry.find("douyin.lead_acquisition", "douyin-research")).isPresent();
    }

    @Test
    void rejectsAdapterThatReferencesUnknownCapability() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SkillV2Manifest manifest = new SkillV2Manifest(
                "demo.skill",
                "0.1.0",
                "v2",
                "Demo",
                "Demo",
                "test",
                List.of(),
                List.of(new CapabilityDefinition("known", "Known", "Known", List.of(), List.of(), List.of(), Map.of())),
                List.of(new AdapterDefinition("bad", "test", "adapters/bad.yaml", List.of("missing"), Map.of())),
                SkillAssetIndex.empty(),
                Map.of());
        SkillPackageV2 skillPackage = new SkillPackageV2(
                manifest,
                objectMapper.readTree("{\"entry\":\"start\",\"steps\":[]}"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null);

        assertThatThrownBy(() -> compiler.compile(skillPackage))
                .isInstanceOf(SkillPackageV2Exception.class)
                .hasMessageContaining("unknown capability: missing");
    }
}
