# Codex Task 18 — Phase 2 F4：PlanExecutionService（concatMap critical, P0-2）

> 你（Codex）要实现 `PlanExecutionService`：
> - 接 `List<ActionRequest>`（F1 ActionPlanner 输出）
> - **逐个串行**走 `ActionExecutionService.execute(...)`（Wave 1 P3 已交付）
> - 任一步 Failure 立即 abort，返回 `PlanResult.Partial(executedPrefix, failedStep)`
> - 全成功返回 `PlanResult.Success(allSuccesses)`
>
> ## 唯一可接受实现方式：`Flux.concatMap`
>
> **NEVER** `flatMap` / `Flux.merge` / `Mono.zip` / `CompletableFuture.allOf` on the action-stream path.
> 这是 **Codex P0-2 严格不变量**。Wave 4 final audit 会 `grep -E 'flatMap|Flux\.merge|Mono\.zip|allOf'` on `vip.mate.browser.orchestrator` —— anything matching = FAIL.
>
> 为什么：视觉指示器（cursor → glow → click）的物理时序依赖串行执行。点击必须在鼠标移动完成（视觉到位）**之后** 发出。flatMap 会并行发，违反时序契约。

## 项目背景

阅读这些文件先理清上下文：
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/ActionExecutionService.java` —— Wave 1 P3，`Mono<ActionResult> execute(BrowserSession, ActionRequest)` API
- `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionRequest.java`
- `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionResult.java` —— sealed `Success | Failure`
- `mateclaw-server/src/main/java/vip/mate/browser/edge/session/BrowserSession.java`

## 你要交付的 3 个文件

### 1. `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/PlanResult.java`

```java
package vip.mate.browser.orchestrator;

import vip.mate.browser.edge.action.ActionResult;
import java.util.List;

public sealed interface PlanResult permits PlanResult.Success, PlanResult.Partial {

    /** Every step succeeded. {@code completed} is in execution order. */
    record Success(List<ActionResult.Success> completed) implements PlanResult {
        public Success {
            if (completed == null) throw new IllegalArgumentException("completed required");
            completed = List.copyOf(completed);
        }
    }

    /**
     * Plan stopped on the first Failure.
     * - {@code completed} = the prefix of steps that succeeded BEFORE the failure (may be empty).
     * - {@code failed}    = the Failure that aborted the plan.
     */
    record Partial(List<ActionResult.Success> completed, ActionResult.Failure failed) implements PlanResult {
        public Partial {
            if (completed == null) throw new IllegalArgumentException("completed required");
            if (failed == null) throw new IllegalArgumentException("failed required");
            completed = List.copyOf(completed);
        }
    }

    static PlanResult success(List<ActionResult.Success> s) { return new Success(s); }
    static PlanResult partial(List<ActionResult.Success> s, ActionResult.Failure f) { return new Partial(s, f); }
}
```

### 2. `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/PlanExecutionService.java`

```java
package vip.mate.browser.orchestrator;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanExecutionService {

    private final ActionExecutionService actionExec;

    /**
     * Execute the plan SEQUENTIALLY. Each step's result is awaited before
     * the next step is sent.
     *
     * The whole point of this service is the concatMap discipline — see
     * the Codex P0-2 invariant in docs/plans/2026-05-28-browser-agent-phase-2.md.
     */
    public Mono<PlanResult> execute(BrowserSession session, List<ActionRequest> plan) {
        if (plan == null || plan.isEmpty()) {
            return Mono.just(PlanResult.success(List.of()));
        }

        return Flux.fromIterable(plan)
                // concatMap — NOT flatMap. Sequential is the whole point.
                .concatMap(req -> Mono.from(actionExec.execute(session, req))
                        .map(result -> new ExecutedStep(req, result)))
                // Stop the chain at the first Failure (collect everything up to
                // and including the failure, then the .map below split it).
                .takeUntil(step -> step.result() instanceof ActionResult.Failure)
                .collectList()
                .map(this::shapeResult);
    }

    private PlanResult shapeResult(List<ExecutedStep> steps) {
        var successes = new java.util.ArrayList<ActionResult.Success>();
        for (var s : steps) {
            switch (s.result()) {
                case ActionResult.Success ok -> successes.add(ok);
                case ActionResult.Failure fail -> {
                    return PlanResult.partial(successes, fail);
                }
            }
        }
        return PlanResult.success(successes);
    }

    private record ExecutedStep(ActionRequest request, ActionResult result) {}
}
```

**Note about `actionExec.execute(...)`**: Wave 1 P3's signature is `Mono<ActionResult> execute(BrowserSession, ActionRequest)`. If the actual method returns `CompletableFuture<ActionResult>` instead (the class uses both at different times in the plan), bridge via `Mono.fromFuture(...)`. Inspect the existing `ActionExecutionService.java` to confirm — use whichever it actually returns.

### 3. `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/PlanExecutionServiceTest.java`

Required tests (~6), use a **fake `ActionExecutionService`** that records call order + return-time wall clock:

```java
class PlanExecutionServiceTest {

    private FakeActionExecutionService fakeExec;
    private PlanExecutionService planExec;
    private BrowserSession session;

    @BeforeEach
    void setup() {
        fakeExec = new FakeActionExecutionService();
        planExec = new PlanExecutionService(fakeExec);
        session = /* construct or mock */;
    }

    @Test
    void emptyPlan_returnsImmediateSuccess() {
        var result = planExec.execute(session, List.of()).block();
        assertThat(result).isInstanceOf(PlanResult.Success.class);
        assertThat(((PlanResult.Success) result).completed()).isEmpty();
    }

    @Test
    void singleStepSuccess_returnsSuccessWithOneResult() { /* */ }

    @Test
    void twoStepPlan_executesSequentially_secondAfterFirstResolves() {
        // FakeActionExecutionService delays its first response by 100ms.
        // After planExec.execute().block(), inspect fakeExec.sendTimestamps()
        // — sendTimestamps[1] - sendTimestamps[0] >= 100ms proves serial.
    }

    @Test
    void onFirstFailure_stopsAndReturnsPartial_doesNotSendRemainingSteps() {
        // Fake returns Failure on step 1; verify exec was only called once
        // (step 2 never sent). PlanResult.Partial.completed is empty;
        // .failed carries the right code/message/retryable.
    }

    @Test
    void onMidPlanFailure_partialCarriesEarlierSuccesses() {
        // 3 steps. Step 1 ok, step 2 fail.
        // Partial.completed has 1 element (step 1's Success);
        // .failed is step 2's Failure; step 3 was never sent.
    }

    @Test
    void noParallelDispatch_invariant() {
        // Build a 5-step plan. Each step takes 50ms in the fake.
        // After block, total elapsed wall-clock >= 5 * 50ms = 250ms
        // (parallel would have been ~50ms).
    }
}

/** Minimal fake that records call order, send times, and returns canned ActionResults. */
static class FakeActionExecutionService extends ActionExecutionService {
    // ... extends or implements the real class; use @MockBean if @SpringBootTest;
    // or write a hand-rolled fake. Either way, MUST record send timestamps
    // so the serial-timing invariant test can verify wall-clock ordering.
}
```

**Important:** if `ActionExecutionService` is `final` or has dependencies you can't easily satisfy, prefer **constructor-injected interface** approach — extract `ActionExecutor` interface, make `ActionExecutionService` implement it, and have `PlanExecutionService` depend on the interface. Then the fake just implements the interface in 5 lines.

## TDD 步骤

1. 写 PlanResult.java + PlanExecutionServiceTest.java → compile error (PlanExecutionService doesn't exist)
2. 跑 `mvn test -Dtest=PlanExecutionServiceTest` → fail
3. 实现 PlanExecutionService.java
4. 跑 → 全绿
5. **Self-grep check before commit**: `grep -E 'flatMap|Flux\.merge|Mono\.zip|allOf' src/main/java/vip/mate/browser/orchestrator/PlanExecutionService.java` MUST return 0 matches.
6. Commit

## Commit message template

```
feat(browser): PlanExecutionService — sequential plan execution via concatMap (F4)

Phase 2 P0-2 invariant: action-stream path uses Flux.concatMap NOT
flatMap / Flux.merge / Mono.zip / CompletableFuture.allOf. The visual
move-then-click timing contract demands strict serialisation.

  emptyPlan          → Success(emptyList)
  allSucceed         → Success(successes in execution order)
  any step Failure   → Partial(successes-before-failure, failure)
                       — remaining steps NEVER sent
  empty plan + null  → Success(emptyList) (defensive)

PlanResult is a sealed interface with Success / Partial records; both
defensively-copy their list payload.

6 PlanExecutionServiceTest cases incl. the wall-clock serial-timing
invariant (5×50ms steps must total >=250ms).

Phase 2 Wave 4 — task F4.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- **不要用 `flatMap` / `Flux.merge` / `Mono.zip` / `CompletableFuture.allOf` 在 action-stream 路径上**。这条会被 audit script grep 出来，rejected。
- 不要碰 `ActionExecutionService` —— consume via its existing API only.
- 不要碰 `vip.mate.browser.edge.action.*` —— Wave 0 已固定。
- 不要在 PlanExecutionService 里加 retry / 重试 —— Phase 3 工作。
- 不要让 PlanExecutionService 调 ActionPlanner —— F1 produces, F4 consumes; the caller wires them.
