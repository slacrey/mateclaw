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
                .containsExactly("douyin-research", "crm-export");
        assertThat(skillPackage.workflow().get("entry").asText()).isEqualTo("discover");
        assertThat(skillPackage.adapters()).containsKeys(
                "adapters/douyin-research.yaml",
                "adapters/crm-export.yaml");
        assertThat(skillPackage.schemas()).containsKeys(
                "schemas/lead-search-request.schema.json",
                "schemas/lead-record.schema.json",
                "schemas/outreach-plan.schema.json");
        assertThat(skillPackage.prompts()).containsKey("prompts/research-system.md");
        assertThat(skillPackage.templates()).containsKey("templates/lead-report.md");
        assertThat(skillPackage.evals()).containsKey("evals/lead-quality.eval.yaml");
    }
}
