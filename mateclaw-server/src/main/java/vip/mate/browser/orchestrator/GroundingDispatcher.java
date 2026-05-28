package vip.mate.browser.orchestrator;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.GroundingEngine;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.engine.A11yEngine;
import vip.mate.browser.orchestrator.engine.DomEngine;
import vip.mate.browser.orchestrator.engine.VisionEngine;
import vip.mate.browser.orchestrator.snapshot.PageSnapshotService;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GroundingDispatcher {

    private final DomEngine dom;
    private final A11yEngine a11y;
    private final VisionEngine vision;
    private final PageSnapshotService snapshotService;

    public GroundingResult ground(BrowserSession session, TabRef tabRef, GroundingHint hint) {
        PageSnapshot snapshot = snapshotService.request(session, tabRef, hint.filter()).block();
        GroundingResult.Ambiguous firstAmbiguous = null;

        for (GroundingEngine engine : List.of(dom, a11y, vision)) {
            GroundingResult result = engine.ground(snapshot, hint);
            switch (result) {
                case GroundingResult.Hit hit -> {
                    return hit;
                }
                case GroundingResult.Ambiguous ambiguous -> {
                    if (firstAmbiguous == null) {
                        firstAmbiguous = ambiguous;
                    }
                }
                case GroundingResult.Miss ignored -> {
                }
            }
        }

        if (firstAmbiguous != null) {
            return firstAmbiguous;
        }
        return new GroundingResult.Miss("no-engine-hit");
    }
}
