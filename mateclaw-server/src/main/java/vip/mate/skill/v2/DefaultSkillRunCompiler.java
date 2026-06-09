package vip.mate.skill.v2;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class DefaultSkillRunCompiler implements SkillRunCompiler {

    private final CapabilityRegistry capabilityRegistry;
    private final AdapterRegistry adapterRegistry;

    public DefaultSkillRunCompiler(CapabilityRegistry capabilityRegistry, AdapterRegistry adapterRegistry) {
        this.capabilityRegistry = capabilityRegistry;
        this.adapterRegistry = adapterRegistry;
    }

    @Override
    public CompiledSkillRun compile(SkillPackageV2 skillPackage) {
        if (skillPackage == null || skillPackage.manifest() == null) {
            throw new SkillPackageV2Exception("Skill package and manifest are required");
        }
        if (skillPackage.workflow() == null || !skillPackage.workflow().isObject()) {
            throw new SkillPackageV2Exception("workflow.json must contain a JSON object");
        }
        validateAdapterBindings(skillPackage);
        capabilityRegistry.register(skillPackage);
        adapterRegistry.register(skillPackage);
        return new CompiledSkillRun(
                skillPackage.id(),
                skillPackage.manifest().version(),
                skillPackage.workflow(),
                skillPackage.manifest().capabilities(),
                skillPackage.manifest().adapters(),
                skillPackage.schemas(),
                skillPackage.prompts(),
                skillPackage.templates(),
                skillPackage.evals());
    }

    private static void validateAdapterBindings(SkillPackageV2 skillPackage) {
        Set<String> capabilityIds = new HashSet<>();
        for (CapabilityDefinition capability : skillPackage.manifest().capabilities()) {
            capabilityIds.add(capability.id());
        }
        for (AdapterDefinition adapter : skillPackage.manifest().adapters()) {
            for (String capabilityId : adapter.capabilities()) {
                if (!capabilityIds.contains(capabilityId)) {
                    throw new SkillPackageV2Exception("Adapter " + adapter.id()
                            + " references unknown capability: " + capabilityId);
                }
            }
        }
    }
}
