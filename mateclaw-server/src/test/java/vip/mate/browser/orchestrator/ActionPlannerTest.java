package vip.mate.browser.orchestrator;

import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionPlannerTest {

    private final ActionPlanner planner = new ActionPlanner();

    @Test
    void clickStep_emitsMoveMouseThenClick() {
        var plan = planner.plan(clickStep(new TabRef.Main(), new BBox(540, 320, 80, 32)));

        assertThat(plan)
                .hasSize(2)
                .extracting(ActionRequest::kind)
                .containsExactly(ActionKind.MOVE_MOUSE, ActionKind.CLICK);
    }

    @Test
    void clickStep_movePayloadHasCenterCoords_naturalProfile() {
        var plan = planner.plan(clickStep(new TabRef.Main(), new BBox(540, 320, 80, 32)));

        var move = (MoveMousePayload) plan.get(0).params();
        assertThat(move.x()).isEqualTo(580);
        assertThat(move.y()).isEqualTo(336);
        assertThat(move.profile()).isEqualTo("natural");
    }

    @Test
    void clickStep_clickPayloadHasSameCenterCoords_leftButtonSingleClick() {
        var plan = planner.plan(clickStep(new TabRef.Main(), new BBox(540, 320, 80, 32)));

        var click = (ClickPayload) plan.get(1).params();
        assertThat(click.x()).isEqualTo(580);
        assertThat(click.y()).isEqualTo(336);
        assertThat(click.button()).isEqualTo("left");
        assertThat(click.clickCount()).isEqualTo(1);
    }

    @Test
    void allProducedRequestsCarryStepTabRef_main() {
        var tabRef = new TabRef.Main();
        var plan = planner.plan(clickStep(tabRef, new BBox(10, 20, 30, 40)));

        assertThat(plan)
                .extracting(ActionRequest::tabRef)
                .containsOnly(tabRef);
    }

    @Test
    void allProducedRequestsCarryStepTabRef_explicit42() {
        var tabRef = new TabRef.Explicit(42);
        var plan = planner.plan(typeStep(tabRef, new BBox(10, 20, 30, 40), "hello"));

        assertThat(plan)
                .extracting(ActionRequest::tabRef)
                .containsOnly(tabRef);
    }

    @Test
    void typeStep_emitsMoveMouseClickType() {
        var plan = planner.plan(typeStep(new TabRef.Main(), new BBox(540, 320, 80, 32), "hello"));

        assertThat(plan)
                .hasSize(3)
                .extracting(ActionRequest::kind)
                .containsExactly(ActionKind.MOVE_MOUSE, ActionKind.CLICK, ActionKind.TYPE);
        var type = (TypePayload) plan.get(2).params();
        assertThat(type.text()).isEqualTo("hello");
    }

    @Test
    void typeStep_clicksFocusBeforeTyping_andTypePayloadHasNoSeparateFocusTarget() {
        var plan = planner.plan(typeStep(new TabRef.Main(), new BBox(540, 320, 80, 32), "hello"));

        var click = (ClickPayload) plan.get(1).params();
        var type = (TypePayload) plan.get(2).params();
        assertThat(click.x()).isEqualTo(580);
        assertThat(click.y()).isEqualTo(336);
        assertThat(type.focusTarget()).isNull();
    }

    @Test
    void ambiguousGrounding_throwsGroundingAmbiguousException() {
        var t1 = new GroundedTarget(new BBox(100, 200, 80, 32));
        var t2 = new GroundedTarget(new BBox(500, 200, 80, 32));
        var step = new Step.ClickStep(
                new TabRef.Main(),
                new GroundingResult.Ambiguous(List.of(t1, t2), "two Submits"));

        assertThatThrownBy(() -> planner.plan(step))
                .isInstanceOf(GroundingAmbiguousException.class)
                .hasMessageContaining("two Submits");
    }

    @Test
    void missGrounding_throwsGroundingMissException() {
        var step = new Step.ClickStep(
                new TabRef.Main(),
                new GroundingResult.Miss("no matching Submit"));

        assertThatThrownBy(() -> planner.plan(step))
                .isInstanceOf(GroundingMissException.class)
                .hasMessageContaining("no matching Submit");
    }

    @Test
    void everyActionRequestHasUniqueMsgId_andSensibleDeadline() {
        var plan = planner.plan(typeStep(new TabRef.Main(), new BBox(540, 320, 80, 32), "hello"));

        assertThat(plan.stream().map(ActionRequest::msgId).distinct().count())
                .isEqualTo(plan.size());
        assertThat(plan)
                .allMatch(r -> r.deadlineMs() >= 1_000);
    }

    @Test
    void clickPayloadDefaultsButton_left_clickCount_1() {
        var plan = planner.plan(clickStep(new TabRef.Main(), new BBox(0, 0, 10, 10)));

        var click = (ClickPayload) plan.get(1).params();
        assertThat(click.button()).isEqualTo("left");
        assertThat(click.clickCount()).isEqualTo(1);
    }

    private static Step.ClickStep clickStep(TabRef tabRef, BBox bbox) {
        return new Step.ClickStep(tabRef, hit(bbox));
    }

    private static Step.TypeStep typeStep(TabRef tabRef, BBox bbox, String text) {
        return new Step.TypeStep(tabRef, hit(bbox), text);
    }

    private static GroundingResult.Hit hit(BBox bbox) {
        return new GroundingResult.Hit(new GroundedTarget(bbox), "test");
    }
}
