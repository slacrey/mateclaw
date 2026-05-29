package vip.mate.browser.orchestrator.screenshot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Fallback {@link ScreenshotEdgeClient} so the Spring context can boot in
 * early-Phase test contexts that don't register a real WS-backed impl.
 *
 * <p>Mirrors {@code SnapshotEdgeClientFallbackConfig} (Wave 2.1-A): the
 * fallback bean throws on any actual call so a misconfigured deploy fails
 * fast with a typed error instead of silently no-op-ing. Once
 * {@link DefaultScreenshotEdgeClient} (the {@code @Service}-annotated real
 * impl) is on the classpath, this fallback is automatically displaced by
 * {@code @ConditionalOnMissingBean}.
 *
 * <p>Use cases:
 * <ul>
 *   <li>App-context startup succeeds even when only the port is shipped
 *       (so unrelated bootstrap smoke tests still pass).</li>
 *   <li>Unit tests like {@code VisionEngineTest} inject their own mocked
 *       {@link ScreenshotEdgeClient} directly into the constructor — this
 *       fallback never runs there.</li>
 *   <li>Any code that <em>actually</em> tries to fetch a screenshot via the
 *       wire without a real impl registered fails fast with a clear error.</li>
 * </ul>
 */
@Configuration
public class ScreenshotEdgeClientFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(ScreenshotEdgeClient.class)
    public ScreenshotEdgeClient screenshotEdgeClientFallback() {
        return (session, tabRef, scaleFactor) -> Mono.error(new IllegalStateException(
                "ScreenshotEdgeClient has no concrete implementation registered. "
                        + "Wave 3-A2 ships the port + Default impl; if that bean is missing "
                        + "the deployment is misconfigured. Inject a mock in tests, or "
                        + "register a real bean in production."));
    }
}
