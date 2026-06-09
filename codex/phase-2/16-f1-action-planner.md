# Codex Task 16 — Phase 2 F1：ActionPlanner

> 你（Codex）要实现 `ActionPlanner`：把一个高层 `Step`（含 `TabRef` + `GroundingResult`）
> 展开为有序的 `List<ActionRequest>`。
> - `ClickStep` → `[move_mouse, click]`（两步）
> - `TypeStep`  → `[move_mouse, click, type]`（三步，先点 focus 后打字）
> - 每个 `ActionRequest` **继承 step 的 TabRef**（Codex P0-1：同一 step 不可跨 tab）
> - `Ambiguous` grounding → 抛 `GroundingAmbiguousException`（**不**降级 click 第一个候选 — Codex P1-9）
> - `Miss` grounding → 抛 `GroundingMissException`

## 项目背景

阅读这些文件先理清上下文（Wave 4-0 已落地）：
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/domain/Step.java` —— sealed `ClickStep | TypeStep`
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/domain/GroundingResult.java` —— sealed `Hit | Ambiguous | Miss`
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/domain/GroundedTarget.java`
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/domain/BBox.java` —— 有 `.center()` 返回 `Point(x,y)`
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/domain/GroundingAmbiguousException.java`
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/domain/GroundingMissException.java`
- `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionRequest.java` —— `record(msgId, tabRef, kind, params, deadlineMs)`
- `mateclaw-server/src/main/java/vip/mate/browser/edge/action/MoveMousePayload.java` —— `record(double x, double y, String profile)`
- `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ClickPayload.java` —— `record(double x, double y, String button, int clickCount)`
- `mateclaw-server/src/main/java/vip/mate/browser/edge/action/TypePayload.java` —— `record(String text, FocusTarget focusTarget)`

## 你要交付的 2 个文件

### 1. `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/ActionPlanner.java`

```java
package vip.mate.browser.orchestrator;

@Service
public class ActionPlanner {

    /** Sensible default deadline for atomic actions. Callers can pass an
     *  override later — Phase 2 hardcodes. */
    private static final long DEFAULT_DEADLINE_MS = 15_000L;

    /** Default natural move profile — B7 (move_mouse) handler honors this. */
    private static final String MOVE_PROFILE = "natural";

    public List<ActionRequest> plan(Step step) {
        // Unwrap grounding — Ambiguous and Miss bail out fast.
        var target = switch (step.grounding()) {
            case GroundingResult.Hit hit -> hit.target();
            case GroundingResult.Ambiguous a -> throw new GroundingAmbiguousException(a);
            case GroundingResult.Miss m -> throw new GroundingMissException(m);
        };
        var center = target.bbox().center();

        return switch (step) {
            case Step.ClickStep cs -> List.of(
                    moveTo(cs.tabRef(), center),
                    clickAt(cs.tabRef(), center)
            );
            case Step.TypeStep ts -> List.of(
                    moveTo(ts.tabRef(), center),
                    clickAt(ts.tabRef(), center),
                    typeText(ts.tabRef(), ts.text())
            );
        };
    }

    private ActionRequest moveTo(TabRef tabRef, BBox.Point p) { /* ... */ }
    private ActionRequest clickAt(TabRef tabRef, BBox.Point p) { /* ... */ }
    private ActionRequest typeText(TabRef tabRef, String text) { /* ... */ }

    private static String uuid() { return UUID.randomUUID().toString(); }
}
```

### 2. `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/ActionPlannerTest.java`

Required test cases (8+):

```java
@Test
void clickStep_emitsMoveMouseThenClick() { /* assertThat(plan).hasSize(2); kinds = [MOVE_MOUSE, CLICK] */ }

@Test
void clickStep_movePayloadHasCenterCoords_naturalProfile() {
    // BBox(540, 320, 80, 32) → center (580, 336); profile="natural"
}

@Test
void clickStep_clickPayloadHasSameCenterCoords_leftButtonSingleClick() { /* */ }

@Test
void allProducedRequestsCarryStepTabRef_main() {
    // assertThat(plan).extracting(ActionRequest::tabRef).containsOnly(new TabRef.Main());
}

@Test
void allProducedRequestsCarryStepTabRef_explicit42() {
    // verify TabRef.Explicit(42) is propagated to every emitted ActionRequest
}

@Test
void typeStep_emitsMoveMouseClickType() { /* hasSize(3); kinds = [MOVE_MOUSE, CLICK, TYPE]; type text matches */ }

@Test
void ambiguousGrounding_throwsGroundingAmbiguousException() {
    var t1 = new GroundedTarget(new BBox(100, 200, 80, 32));
    var t2 = new GroundedTarget(new BBox(500, 200, 80, 32));
    var step = new Step.ClickStep(new TabRef.Main(),
            new GroundingResult.Ambiguous(List.of(t1, t2), "two Submits"));
    assertThatThrownBy(() -> planner.plan(step))
            .isInstanceOf(GroundingAmbiguousException.class)
            .hasMessageContaining("two Submits");
}

@Test
void missGrounding_throwsGroundingMissException() { /* */ }

@Test
void everyActionRequestHasUniqueMsgId_andSensibleDeadline() {
    // assert plan.stream().map(msgId).distinct().count() == plan.size();
    // assert plan.stream().allMatch(r -> r.deadlineMs() >= 1_000);
}

@Test
void clickPayloadDefaultsButton_left_clickCount_1() { /* */ }
```

## TDD 步骤

1. 写 ActionPlannerTest.java，全 fail / compile error
2. 跑 `mvn test -Dtest=ActionPlannerTest` → fail
3. 实现 ActionPlanner.java
4. 跑 → 全绿
5. Commit

## Commit message template

```
feat(browser): ActionPlanner — expands high-level Step into atomic action sequences (F1)

ClickStep → [move_mouse, click]
TypeStep  → [move_mouse, click, type]   (click focuses before typing)

Every emitted ActionRequest carries the step's TabRef (P0-1: no
cross-tab drift). Ambiguous grounding throws
GroundingAmbiguousException; Miss throws GroundingMissException —
the planner does NOT silently click the first candidate (P1-9).

move_mouse uses profile="natural" by default (consumed by Wave-2
B7 handler via WindMouse). The single move_mouse is the natural-
profile call; the planner does NOT emit N small moves.

10 ActionPlannerTest cases.

Phase 2 Wave 4 — task F1.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要碰 `ActionExecutionService` (Wave 1 P3) —— planner emits requests, doesn't execute them; that's F4's job.
- 不要碰 `PageSnapshotService` or any grounding engine —— the Step already carries `GroundingResult` (set upstream by GroundingDispatcher / F2).
- 不要在 planner 里加并发逻辑 —— 它就是个无状态的 switch；F4 (PlanExecutionService) owns sequential dispatch.
- 不要碰 `vip.mate.browser.orchestrator.domain.*` —— Wave 4-0 已固定。
