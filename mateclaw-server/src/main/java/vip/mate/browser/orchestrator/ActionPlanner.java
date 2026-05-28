package vip.mate.browser.orchestrator;

import org.springframework.stereotype.Service;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ClickPayload;
import vip.mate.browser.edge.action.MoveMousePayload;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.action.TypePayload;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingAmbiguousException;
import vip.mate.browser.orchestrator.domain.GroundingMissException;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.Step;

import java.util.List;
import java.util.UUID;

@Service
public class ActionPlanner {

    /** Sensible default deadline for atomic actions. Phase 2 hardcodes it. */
    private static final long DEFAULT_DEADLINE_MS = 15_000L;

    /** Default natural move profile — the move_mouse handler honors this. */
    private static final String MOVE_PROFILE = "natural";

    public List<ActionRequest> plan(Step step) {
        GroundedTarget target = switch (step.grounding()) {
            case GroundingResult.Hit hit -> hit.target();
            case GroundingResult.Ambiguous ambiguous -> throw new GroundingAmbiguousException(ambiguous);
            case GroundingResult.Miss miss -> throw new GroundingMissException(miss);
        };
        BBox.Point center = target.bbox().center();

        return switch (step) {
            case Step.ClickStep click -> List.of(
                    moveTo(click.tabRef(), center),
                    clickAt(click.tabRef(), center)
            );
            case Step.TypeStep type -> List.of(
                    moveTo(type.tabRef(), center),
                    clickAt(type.tabRef(), center),
                    typeText(type.tabRef(), type.text())
            );
        };
    }

    private ActionRequest moveTo(TabRef tabRef, BBox.Point p) {
        return new ActionRequest(
                uuid(),
                tabRef,
                ActionKind.MOVE_MOUSE,
                new MoveMousePayload(p.x(), p.y(), MOVE_PROFILE),
                DEFAULT_DEADLINE_MS);
    }

    private ActionRequest clickAt(TabRef tabRef, BBox.Point p) {
        return new ActionRequest(
                uuid(),
                tabRef,
                ActionKind.CLICK,
                new ClickPayload(p.x(), p.y(), "left", 1),
                DEFAULT_DEADLINE_MS);
    }

    private ActionRequest typeText(TabRef tabRef, String text) {
        return new ActionRequest(
                uuid(),
                tabRef,
                ActionKind.TYPE,
                new TypePayload(text, null),
                DEFAULT_DEADLINE_MS);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
