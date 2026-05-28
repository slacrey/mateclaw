package vip.mate.browser.orchestrator.engine;

import org.springframework.stereotype.Component;
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

    @Override
    public GroundingResult ground(PageSnapshot snapshot, GroundingHint hint) {
        return new GroundingResult.Miss("stub-phase-2: A11y engine not yet implemented");
    }
}
