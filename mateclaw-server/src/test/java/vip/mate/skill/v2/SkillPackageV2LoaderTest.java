package vip.mate.skill.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPackageV2LoaderTest {

    private final SkillPackageV2Loader loader = new SkillPackageV2Loader(new ObjectMapper());

    @Test
    void loadsBundledDouyinSkillPackage() {
        SkillPackageV2 skillPackage = loader.loadBundled("douyin-lead-acquisition");

        assertThat(skillPackage.id()).isEqualTo("douyin.lead_acquisition");
        assertThat(skillPackage.manifest().isV2()).isTrue();
        assertThat(skillPackage.manifest().capabilities())
                .extracting(CapabilityDefinition::id)
                .containsExactly("discover_leads", "qualify_leads", "draft_outreach");
        assertThat(skillPackage.manifest().adapters())
                .extracting(AdapterDefinition::id)
                .containsExactly("douyin", "crm-export");
        assertThat(skillPackage.workflow().get("apiVersion").asText()).isEqualTo("mateclaw.workflow/v2");
        assertThat(skillPackage.workflow().get("kind").asText()).isEqualTo("Workflow");
        assertThat(skillPackage.adapters()).containsKeys(
                "adapters/douyin.yaml",
                "adapters/crm-export.yaml");
        assertThat(skillPackage.schemas()).containsKeys(
                "schemas/input.schema.json",
                "schemas/lead-comment.schema.json",
                "schemas/output.schema.json");
        assertThat(skillPackage.prompts()).containsKey("prompts/research-system.md");
        assertThat(skillPackage.templates()).containsKey("templates/lead-report.md");
        assertThat(skillPackage.evals()).containsKey("evals/lead-quality.eval.yaml");
    }
}
