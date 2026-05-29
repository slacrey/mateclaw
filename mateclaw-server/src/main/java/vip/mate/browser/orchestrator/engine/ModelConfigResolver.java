package vip.mate.browser.orchestrator.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;

import java.util.Optional;

/**
 * Resolves which {@link ModelConfigEntity} the {@link VisionEngine} should
 * use to ground a screenshot.
 *
 * <p><strong>Phase 3 Wave A approach:</strong> a single global config key
 * {@code mateclaw.browser.vision.model-id} names the vision model by its
 * {@code model_name} (e.g. {@code qwen-vl-plus}); the resolver looks it up
 * via {@link ModelConfigService#findEnabledModel(String, String)} or
 * scans enabled rows by name. When unset or unresolvable the resolver
 * returns {@link Optional#empty()} so VisionEngine fails soft to
 * {@link vip.mate.browser.orchestrator.domain.GroundingResult.Miss}.
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

    private final ModelConfigService modelConfigService;
    private final String configuredModelName;

    public ModelConfigResolver(
            ModelConfigService modelConfigService,
            @Value("${mateclaw.browser.vision.model-id:default-vision}") String configuredModelName) {
        this.modelConfigService = modelConfigService;
        this.configuredModelName = configuredModelName == null ? DEFAULT_SENTINEL : configuredModelName.trim();
    }

    /**
     * Resolve a vision model for browser grounding.
     *
     * @return the resolved model, or {@link Optional#empty()} when no
     *         vision model is configured / matchable.
     */
    public Optional<ModelConfigEntity> resolveVisionModel() {
        if (configuredModelName.isBlank() || DEFAULT_SENTINEL.equals(configuredModelName)) {
            log.debug("[VisionEngine] no vision model configured (mateclaw.browser.vision.model-id unset)");
            return Optional.empty();
        }
        try {
            ModelConfigEntity match = modelConfigService.listEnabledModels().stream()
                    .filter(m -> configuredModelName.equalsIgnoreCase(m.getModelName())
                            || configuredModelName.equalsIgnoreCase(m.getName()))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                log.warn("[VisionEngine] configured vision model '{}' not found in enabled models",
                        configuredModelName);
                return Optional.empty();
            }
            return Optional.of(match);
        } catch (Exception e) {
            log.warn("[VisionEngine] error resolving vision model '{}': {}",
                    configuredModelName, e.getMessage());
            return Optional.empty();
        }
    }
}
