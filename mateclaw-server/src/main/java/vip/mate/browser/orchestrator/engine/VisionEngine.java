package vip.mate.browser.orchestrator.engine;

import org.springframework.stereotype.Component;
import vip.mate.browser.orchestrator.domain.GroundingEngine;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;

@Component
public class VisionEngine implements GroundingEngine {

    @Override
    public String name() {
        return "vision";
    }

    @Override
    public GroundingResult ground(PageSnapshot snapshot, GroundingHint hint) {
        return new GroundingResult.Miss("stub-phase-2: Vision engine not yet implemented");
    }
}
