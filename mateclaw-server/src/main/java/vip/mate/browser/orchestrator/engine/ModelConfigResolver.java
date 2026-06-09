package vip.mate.browser.orchestrator.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelCapabilityService.Modality;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.service.SystemSettingService;

import java.util.List;
import java.util.Optional;

/**
 * Resolves which {@link ModelConfigEntity} the {@link VisionEngine} should
 * use to ground a screenshot.
 *
 * <p><strong>Resolution order:</strong> the Settings → Models → Multimodal
 * sidecar vision model ({@code mate_system_setting.default.vision_model})
 * wins, because it is the user-facing UI for this setting. The legacy
 * {@code mateclaw.browser.vision.model-id} property remains as an ops-only
 * override when the UI setting is empty. When both are unset, auto-discovery
 * selects a configured vision-capable model.
 *
 * <p><strong>TODO (Phase 4):</strong> resolution should consult the active
 * tenant / digital-employee binding instead of a global property so each
 * employee can prefer its own vision model. Until then a single shared
 * model is acceptable — vision grounding is a fallback path used after
 * DOM + A11y, so a misconfigured environment degrades to "vision off",
 * not to "browser agent broken".
 */
@Slf4j
@Component
public class ModelConfigResolver {

    /** Default sentinel that triggers "no model configured" semantics. */
    public static final String DEFAULT_SENTINEL = "default-vision";

    private static final String DEFAULT_VISION_MODEL_KEY = "default.vision_model";

    private final ModelConfigService modelConfigService;
    private final ModelCapabilityService capabilityService;
    private final ModelProviderService providerService;
    private final SystemSettingService systemSettingService;
    private final String configuredModelName;

    public ModelConfigResolver(
            ModelConfigService modelConfigService,
            ModelCapabilityService capabilityService,
            ModelProviderService providerService,
            SystemSettingService systemSettingService,
            @Value("${mateclaw.browser.vision.model-id:default-vision}") String configuredModelName) {
        this.modelConfigService = modelConfigService;
        this.capabilityService = capabilityService;
        this.providerService = providerService;
        this.systemSettingService = systemSettingService;
        this.configuredModelName = configuredModelName == null ? DEFAULT_SENTINEL : configuredModelName.trim();
    }

    /**
     * Resolve a vision model for browser grounding.
     *
     * <p>Two modes:
     * <ul>
     *   <li><strong>UI sidecar setting</strong> — {@code default.vision_model}
     *       stores a {@code mate_model_config.id}. This is what the Settings UI
     *       writes.</li>
     *   <li><strong>Legacy explicit pin</strong> —
     *       {@code mateclaw.browser.vision.model-id} names a model; we look it
     *       up among enabled models (by modelName or display name).</li>
     *   <li><strong>Auto-discovery</strong> (the default, when the property is
     *       unset) — pick an enabled, vision-capable chat model so screenshot
     *       grounding works out of the box. Vision is only a FALLBACK (it runs
     *       after DOM + A11y both miss), so turning it on whenever a capable
     *       model exists is safe and is exactly what lets the agent click
     *       elements the a11y tree can't expose as interactive — custom
     *       filter-panel options, canvas/SVG controls, icon buttons, etc.</li>
     * </ul>
     *
     * @return the resolved model, or {@link Optional#empty()} when no
     *         vision-capable model is available (vision grounding stays off).
     */
    public Optional<ModelConfigEntity> resolveVisionModel() {
        try {
            Optional<ModelConfigEntity> sidecarModel = resolveSidecarVisionModel();
            if (sidecarModel.isPresent()) {
                return sidecarModel;
            }

            List<ModelConfigEntity> enabled = modelConfigService.listEnabledModels();

            // Legacy explicit pin wins when the UI sidecar setting is empty.
            if (!configuredModelName.isBlank() && !DEFAULT_SENTINEL.equals(configuredModelName)) {
                ModelConfigEntity match = enabled.stream()
                        .filter(m -> configuredModelName.equalsIgnoreCase(m.getModelName())
                                || configuredModelName.equalsIgnoreCase(m.getName()))
                        .findFirst()
                        .orElse(null);
                if (match == null) {
                    log.warn("[VisionEngine] configured vision model '{}' not found in enabled models",
                            configuredModelName);
                    return Optional.empty();
                }
                if (!isVisionCapable(match)) {
                    log.warn("[VisionEngine] configured vision model '{}' is not vision-capable; "
                            + "screenshot grounding will likely fail", configuredModelName);
                }
                if (!isProviderConfigured(match)) {
                    log.warn("[VisionEngine] configured vision model '{}' belongs to provider '{}' which is "
                            + "NOT configured (missing API Key / Base URL); the vision call will fail until you "
                            + "configure it in Settings→Models", configuredModelName, match.getProvider());
                }
                return Optional.of(match);
            }

            // Auto-discovery: among enabled vision-capable chat models, pick the
            // one best suited to GUI grounding. Coordinate grounding is a
            // specialized skill — dedicated VL models (Qwen-VL, Gemini) return
            // far tighter pixel boxes than a general multimodal chat model, so we
            // rank by grounding suitability FIRST and only use the default-flagged
            // model as a tie-breaker. (Grounding ≠ chatting, so we don't just
            // reuse the user's default chat model.)
            //
            // CRITICAL: a model row whose PROVIDER has no API Key / Base URL can
            // never actually be called — buildFor() throws "Provider 未完成配置".
            // Many seeded VL rows (e.g. qwen3-vl-* under an unconfigured provider)
            // are `enabled` yet unusable, so we MUST filter to configured providers
            // BEFORE ranking — otherwise auto-discovery happily picks an unusable
            // row and every grounding attempt fails even though a usable VL model
            // (under the configured provider) was right there.
            List<ModelConfigEntity> capable = enabled.stream()
                    .filter(this::isChatModel)
                    .filter(this::isVisionCapable)
                    .toList();
            List<ModelConfigEntity> visionModels = capable.stream()
                    .filter(this::isProviderConfigured)
                    .toList();
            if (visionModels.isEmpty()) {
                if (!capable.isEmpty()) {
                    ModelConfigEntity sample = capable.get(0);
                    log.warn("[VisionEngine] auto-discovery: {} vision-capable model(s) are enabled but NONE "
                            + "have a configured provider (e.g. '{}' via provider '{}'). Configure that "
                            + "provider's API Key in Settings→Models to enable screenshot grounding.",
                            capable.size(), sample.getModelName(), sample.getProvider());
                } else {
                    log.debug("[VisionEngine] auto-discovery: no enabled vision-capable model — "
                            + "vision grounding disabled (enable a VL model, or set mateclaw.browser.vision.model-id)");
                }
                return Optional.empty();
            }
            ModelConfigEntity chosen = visionModels.stream()
                    .min(java.util.Comparator
                            .comparingInt((ModelConfigEntity m) -> groundingRank(m.getModelName()))
                            .thenComparing(m -> Boolean.TRUE.equals(m.getIsDefault()) ? 0 : 1))
                    .orElse(visionModels.get(0));
            log.debug("[VisionEngine] auto-selected vision model '{}' ({}) for grounding",
                    chosen.getName(), chosen.getModelName());
            return Optional.of(chosen);
        } catch (Exception e) {
            log.warn("[VisionEngine] error resolving vision model: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ModelConfigEntity> resolveSidecarVisionModel() {
        String raw = systemSettingService.getString(DEFAULT_VISION_MODEL_KEY, "");
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Long modelId;
        try {
            modelId = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[VisionEngine] configured sidecar vision model id '{}' is not a valid number", raw);
            return Optional.empty();
        }

        ModelConfigEntity match;
        try {
            match = modelConfigService.getModel(modelId);
        } catch (Exception e) {
            log.warn("[VisionEngine] configured sidecar vision model id '{}' could not be loaded: {}",
                    modelId, e.getMessage());
            return Optional.empty();
        }
        if (!Boolean.TRUE.equals(match.getEnabled())) {
            log.warn("[VisionEngine] configured sidecar vision model '{}/{}' is disabled",
                    match.getProvider(), match.getModelName());
            return Optional.empty();
        }
        if (!isChatModel(match)) {
            log.warn("[VisionEngine] configured sidecar vision model '{}/{}' is model_type='{}', not chat",
                    match.getProvider(), match.getModelName(), match.getModelType());
            return Optional.empty();
        }
        if (!isVisionCapable(match)) {
            log.warn("[VisionEngine] configured sidecar vision model '{}/{}' is not vision-capable; "
                    + "ignoring it and falling back to legacy pin / auto-discovery",
                    match.getProvider(), match.getModelName());
            return Optional.empty();
        }
        if (!isProviderConfigured(match)) {
            log.warn("[VisionEngine] configured sidecar vision model '{}/{}' provider '{}' is NOT configured; "
                    + "the vision call will fail until you configure it in Settings→Models",
                    match.getProvider(), match.getModelName(), match.getProvider());
        }
        log.debug("[VisionEngine] selected sidecar vision model '{} / {}' for grounding",
                match.getProvider(), match.getModelName());
        return Optional.of(match);
    }

    /** chat (multimodal) models only — embedding/rerank can't ground a screenshot. */
    private boolean isChatModel(ModelConfigEntity m) {
        String t = m.getModelType();
        return t == null || t.isBlank() || "chat".equalsIgnoreCase(t);
    }

    private boolean isVisionCapable(ModelConfigEntity m) {
        return capabilityService.supports(m.getModelName(), m.getModalities(), Modality.VISION);
    }

    /**
     * Whether the model's provider has usable credentials (API Key / Base URL,
     * per the provider's protocol requirements). A vision model whose provider
     * is unconfigured is unusable — {@code buildFor()} throws
     * "Provider 未完成配置" — so it must never be auto-selected. Defensive: any
     * lookup error is treated as "not configured" so a transient failure simply
     * skips the row rather than throwing out of resolution.
     */
    private boolean isProviderConfigured(ModelConfigEntity m) {
        try {
            String providerId = m.getProvider();
            return providerId != null && !providerId.isBlank()
                    && providerService.isProviderConfigured(providerId);
        } catch (Exception e) {
            log.debug("[VisionEngine] provider-configured check failed for model '{}' (provider '{}'): {}",
                    m.getModelName(), m.getProvider(), e.getMessage());
            return false;
        }
    }

    /**
     * Model families ordered by GUI-grounding suitability (lower = better). The
     * Qwen-VL line is explicitly trained for visual grounding / GUI agents and
     * returns the tightest pixel boxes; Gemini grounds well; GPT-4o/4.1/5 are
     * decent. Everything else vision-capable (Kimi, Claude, GLM, …) lands in the
     * unranked tail — usable, but not grounding-specialized.
     */
    private static final List<String> GROUNDING_PREFERENCE = List.of(
            "qwen3-vl", "qwen2.5-vl", "qwen2-vl", "qwen-vl", "glm-4.6v", "glm-4.5v", "glm-4.1v", "glm-4v",
            "gemini-2.5", "gemini-2", "gemini-1.5",
            "gpt-4o", "gpt-4.1", "gpt-5");

    private int groundingRank(String modelName) {
        String n = modelName == null ? "" : modelName.toLowerCase();
        for (int i = 0; i < GROUNDING_PREFERENCE.size(); i++) {
            if (n.contains(GROUNDING_PREFERENCE.get(i))) {
                return i;
            }
        }
        return GROUNDING_PREFERENCE.size();
    }
}
