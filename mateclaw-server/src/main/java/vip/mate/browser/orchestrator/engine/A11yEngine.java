package vip.mate.browser.orchestrator.engine;

import org.springframework.stereotype.Component;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.GroundingEngine;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;

@Component
public class A11yEngine implements GroundingEngine {

    @Override
    public String name() {
        return "a11y";
    }

    /**
     * A11y-tree grounding ignores {@code session} and {@code tabRef}: the
     * input snapshot is the only state it consults. The two params are part
     * of the {@link GroundingEngine} contract so {@code VisionEngine} can
     * issue auxiliary edge calls without a separate dispatch path.
     */
    @Override
    public GroundingResult ground(BrowserSession session,
                                  TabRef tabRef,
                                  PageSnapshot snapshot,
                                  GroundingHint hint) {
        return new GroundingResult.Miss("stub-phase-2: A11y engine not yet implemented");
    }
}
