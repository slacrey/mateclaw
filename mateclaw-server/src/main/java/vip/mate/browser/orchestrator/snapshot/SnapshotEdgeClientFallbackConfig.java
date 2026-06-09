package vip.mate.browser.orchestrator.snapshot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.PageSnapshot;

/**
 * Fallback {@link SnapshotEdgeClient} so the Spring context can boot even
 * when no concrete WS-backed implementation is registered yet (the F5 spec
 * deliberately ships only the port — the WS wiring is a follow-up commit).
 *
 * <p>The fallback bean fails any actual call with a clear error; this is
 * intentional. We want:
 * <ul>
 *   <li>App-context startup to succeed (so unrelated bootstrap smoke tests
 *       like {@code EdgeEndpointSmokeTest} still pass).</li>
 *   <li>Unit tests like {@code PageSnapshotServiceTest} to inject their own
 *       mocked {@link SnapshotEdgeClient} directly into the constructor —
 *       this fallback never runs there.</li>
 *   <li>Any code that <em>actually</em> tries to fetch a snapshot via the
 *       wire to fail fast with a typed error pointing at the missing impl,
 *       rather than silently no-op.</li>
 * </ul>
 *
 * <p>Once the concrete WS-backed {@code SnapshotEdgeClient} lands, this
 * fallback is automatically displaced by {@code @ConditionalOnMissingBean}.
 */
@Configuration
public class SnapshotEdgeClientFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(SnapshotEdgeClient.class)
    public SnapshotEdgeClient snapshotEdgeClientFallback() {
        return (session, tabRef, filter) -> Mono.error(new IllegalStateException(
                "SnapshotEdgeClient has no concrete implementation registered. "
                        + "The F5 spec ships only the port; the WS-backed impl is a "
                        + "follow-up commit. Inject a mock in tests, or register a "
                        + "real bean in production."));
    }
}
