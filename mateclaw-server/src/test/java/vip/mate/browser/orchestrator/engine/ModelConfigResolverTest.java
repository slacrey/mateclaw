package vip.mate.browser.orchestrator.engine;

import org.junit.jupiter.api.Test;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelCapabilityService.Modality;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModelConfigResolver} auto-discovery, focused on the
 * provider-configured gate (the fix for: auto-discovery selected a vision model
 * whose provider had no API Key, so every grounding call failed with
 * "Provider 未完成配置" even though a usable VL model existed under the configured
 * provider — exactly the dashscope-compat vs bailian-team situation).
 */
class ModelConfigResolverTest {

    private static final String AUTO = "default-vision";

    private ModelConfigEntity chatModel(String modelName, String provider) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setModelName(modelName);
        m.setName(modelName);
        m.setProvider(provider);
        m.setModelType("chat");
        return m;
    }

    @Test
    void autoDiscovery_skipsUnconfiguredProvider_andPicksConfiguredVisionModel() {
        ModelConfigService cfg = mock(ModelConfigService.class);
        ModelCapabilityService cap = mock(ModelCapabilityService.class);
        ModelProviderService prov = mock(ModelProviderService.class);

        // Same VL family under two providers — one unconfigured (the trap), one
        // configured. Pre-fix this picked the bailian-team row and failed.
        ModelConfigEntity unconfigured = chatModel("qwen3-vl-flash", "bailian-team");
        ModelConfigEntity configured = chatModel("qwen3-vl-plus", "dashscope-compat");
        when(cfg.listEnabledModels()).thenReturn(List.of(unconfigured, configured));
        when(cap.supports(any(), any(), eq(Modality.VISION))).thenReturn(true);
        when(prov.isProviderConfigured("bailian-team")).thenReturn(false);
        when(prov.isProviderConfigured("dashscope-compat")).thenReturn(true);

        Optional<ModelConfigEntity> chosen =
                new ModelConfigResolver(cfg, cap, prov, AUTO).resolveVisionModel();

        assertThat(chosen).isPresent();
        assertThat(chosen.get().getModelName()).isEqualTo("qwen3-vl-plus");
        assertThat(chosen.get().getProvider()).isEqualTo("dashscope-compat");
    }

    @Test
    void autoDiscovery_visionModelsExistButNoProviderConfigured_returnsEmpty() {
        ModelConfigService cfg = mock(ModelConfigService.class);
        ModelCapabilityService cap = mock(ModelCapabilityService.class);
        ModelProviderService prov = mock(ModelProviderService.class);

        when(cfg.listEnabledModels()).thenReturn(List.of(chatModel("qwen3-vl-flash", "bailian-team")));
        when(cap.supports(any(), any(), eq(Modality.VISION))).thenReturn(true);
        when(prov.isProviderConfigured(any())).thenReturn(false);

        assertThat(new ModelConfigResolver(cfg, cap, prov, AUTO).resolveVisionModel()).isEmpty();
    }

    @Test
    void autoDiscovery_noVisionCapableModel_returnsEmpty() {
        ModelConfigService cfg = mock(ModelConfigService.class);
        ModelCapabilityService cap = mock(ModelCapabilityService.class);
        ModelProviderService prov = mock(ModelProviderService.class);

        when(cfg.listEnabledModels()).thenReturn(List.of(chatModel("qwen-max", "dashscope-compat")));
        when(cap.supports(any(), any(), any())).thenReturn(false); // text-only

        assertThat(new ModelConfigResolver(cfg, cap, prov, AUTO).resolveVisionModel()).isEmpty();
    }

    @Test
    void autoDiscovery_amongConfigured_prefersBetterGroundingRank() {
        ModelConfigService cfg = mock(ModelConfigService.class);
        ModelCapabilityService cap = mock(ModelCapabilityService.class);
        ModelProviderService prov = mock(ModelProviderService.class);

        ModelConfigEntity vlMax = chatModel("qwen-vl-max", "dashscope-compat");   // grounding rank ~3
        ModelConfigEntity vlPlus = chatModel("qwen3-vl-plus", "dashscope-compat"); // grounding rank 0 (best)
        when(cfg.listEnabledModels()).thenReturn(List.of(vlMax, vlPlus));
        when(cap.supports(any(), any(), eq(Modality.VISION))).thenReturn(true);
        when(prov.isProviderConfigured("dashscope-compat")).thenReturn(true);

        assertThat(new ModelConfigResolver(cfg, cap, prov, AUTO).resolveVisionModel())
                .get()
                .extracting(ModelConfigEntity::getModelName)
                .isEqualTo("qwen3-vl-plus");
    }
}
