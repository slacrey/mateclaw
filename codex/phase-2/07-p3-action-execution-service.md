# Codex Task 07 — Phase 2 P3：ActionExecutionService

> 你（Codex）要实现 MateClaw Browser Agent Phase 2 的 **action 执行编排服务**：把
> 一个强类型 `ActionRequest` 通过 WebSocket 发到 Native Host，等 `action.result`
> 回来，把它反序列化成 `ActionResult` 返回。包含取消（cancel CAS）和超时。

## 项目背景

- Phase 1 已交付：`EdgeMessage` 协议（v1.1）、`BrowserSession` + `BrowserSessionRegistry`、`EdgeWebSocketHandler`。
- Phase 2 Wave 0 已交付：`ActionKind` / `ActionPayload` / `ActionRequest` / `ActionResult` / `ActionSuccessPayload` / `TabRef`。
- 你的工作是 Control Plane 编排层 —— 把上面那两层粘起来。

阅读这几个文件先理清上下文：
- `mateclaw-server/src/main/java/vip/mate/browser/edge/session/BrowserSessionRegistry.java`（session 查找 + WS 引用）
- `mateclaw-server/src/main/java/vip/mate/browser/edge/protocol/EdgeMessage.java`（lombok @Builder record-style 类）
- `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionRequest.java`（`{msg_id, tab_ref, kind, params, deadline_ms}`）
- `mateclaw-server/src/main/java/vip/mate/browser/edge/action/ActionResult.java`（sealed `Success | Failure`）
- `docs/specs/edge-protocol.md` §"action.execute" 和 §"action.result"

## 你要交付的 4 个文件

放在 **新包** `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/`（Phase 1 没碰过这个包，不冲突）。

### 1. `ActionExecutionService.java`

```java
package vip.mate.browser.orchestrator;

@Service
public class ActionExecutionService {

    private final BrowserSessionRegistry registry;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** msgId → state slot for an in-flight request. */
    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();

    /**
     * Send action.execute on the session's WebSocket and return a Mono that
     * completes when action.result with matching in_reply_to arrives (or
     * deadline expires, or cancel succeeds, or session detaches).
     *
     * Idempotent: same (sessionId, msgId) returns the existing future.
     */
    public Mono<ActionResult> execute(BrowserSession session, ActionRequest req);

    /**
     * Atomically transition the in-flight request to CANCELLING. If a
     * concurrent result arrives at the same moment, exactly one outcome
     * wins (the CAS makes it deterministic):
     *   - cancel-wins: deliver Failure(CANCELLED) to caller, ignore the
     *     incoming result envelope.
     *   - result-wins: deliver the real result; cancel is a no-op.
     *
     * Idempotent: cancelling an already-completed request returns Mono.empty().
     */
    public Mono<Void> cancel(BrowserSession session, String msgId, String reason);

    /**
     * Called by the WebSocket handler when an action.result envelope arrives.
     * Resolves the matching pending future (if any), respecting the CAS
     * with concurrent cancel.
     */
    public void deliverResult(String msgId, ActionResult result);

    /**
     * Called by the WebSocket handler when an indicator.stop_clicked envelope
     * arrives. Looks up the in-flight request on this session and triggers
     * cancel(reason="user_stop"). If there is none in flight, ignore (the
     * user clicked stop after the action completed — common race).
     */
    public void handleStopClicked(BrowserSession session);
}
```

`PendingRequest` is an internal record holding:
- `AtomicReference<State>` where `State = INFLIGHT | CANCELLING | DONE`
- `CompletableFuture<ActionResult>` that callers await
- `Instant deadlineAt` (computed from request's `deadline_ms`)
- `String msgId` for logging

### 2. `ActionExecutionServiceTest.java`（test class — under `src/test/java/.../orchestrator/`）

Required test methods (use Mockito for `BrowserSessionRegistry` and the WS):

```java
@Test void execute_roundTrip_returnsSuccess() {
    // Given a request, when the WS receives matching action.result Success,
    // the Mono completes with that Success.
}

@Test void execute_resultBeforeReturn_isNotLost() {
    // Race: deliverResult is called BEFORE execute() registers the pending
    // map entry. The implementation must handle this — typical fix is
    // pending.put() before ws.sendMessage().
}

@Test void cancel_winsRaceAgainstResult_deliversCancelledFailure() {
    // CAS race scenario:
    //   1. execute(req) returns a Mono (registers PendingRequest)
    //   2. cancel(msgId) is called — transitions to CANCELLING
    //   3. deliverResult(msgId, Success) arrives — must be IGNORED
    //   4. Mono completes with Failure(CANCELLED, ..., retryable=false)
    // Verify: at most one terminal value reaches the subscriber.
}

@Test void cancel_losesRaceAgainstResult_deliversRealResult() {
    // Inverse race:
    //   1. execute(req) returns a Mono
    //   2. deliverResult(msgId, Success) arrives — transitions to DONE
    //   3. cancel(msgId) is called — CAS fails, returns Mono.empty()
    //   4. Mono completes with the real Success
}

@Test void deadlineExpiry_returnsFailure() {
    // ActionRequest.deadlineMs = 100; no result arrives; after 100ms +
    // small tolerance, the Mono completes with Failure(DEADLINE_EXCEEDED,
    // retryable=true).
}

@Test void sessionDetachedMidFlight_returnsFailure() {
    // Trigger: session.close() called while a request is in flight.
    // The implementation should expose a sessionClosed(sessionId) hook
    // that cancels every pending request bound to that session with
    // Failure(SESSION_DETACHED, retryable=false).
}

@Test void execute_idempotency_sameMsgIdReturnsSameMono() {
    // Calling execute() twice with the same (session, msgId) returns the
    // SAME Mono — no double-send on the WS.
}

@Test void handleStopClicked_triggersCancelOnInflight() {
    // Set up: execute(req) in flight; then handleStopClicked(session).
    // Verify: cancel(msgId, "user_stop") was triggered; Mono ends Failure(CANCELLED).
}

@Test void handleStopClicked_noInflight_ignored() {
    // No-op when there's nothing in flight on this session.
}
```

Use a virtual `Clock` + scheduler so the deadline test is fast (not real 100ms sleep).

### 3. `EdgeWebSocketHandler` modification

Find `EdgeWebSocketHandler.java` (in `vip.mate.browser.edge`). When an inbound `EdgeMessage` arrives, **add dispatch**:

```java
case ACTION_RESULT -> {
    // payload contains the {ok, elapsed_ms, payload} OR {ok, code, message, retryable}.
    // Parse into ActionResult, look up in_reply_to → call
    // actionExecutionService.deliverResult(inReplyTo, result).
}
case INDICATOR_STOP_CLICKED -> {
    // Look up the session by ws.id → actionExecutionService.handleStopClicked(session)
}
```

Inject `ActionExecutionService` via constructor. The handler must NOT block — `deliverResult` is non-blocking by design (it just completes a future).

### 4. `OrchestratorConfig.java`（@Configuration）

Provide a bean for the `Clock` (system UTC by default), wire `ActionExecutionService` if Spring's default `@Service` + constructor-inject doesn't work for the registry/handler.

## Concurrency invariants

1. **Single-result rule**: each `PendingRequest` is resolved exactly once. The `AtomicReference<State>` CAS from `INFLIGHT → DONE` (result path) or `INFLIGHT → CANCELLING → DONE` (cancel path) is the gate.
2. **No deadlock**: don't hold the registry's `compute()` lock while waiting on the future.
3. **No leak**: `pending` map entry is removed when terminal state reached (Success / Failure / Cancelled).
4. **Deterministic deadline**: use `Mono.timeout(Duration.ofMillis(req.deadlineMs()))` or a `ScheduledExecutorService` — not `Thread.sleep`.

## TDD step gates (mandatory)

1. Write `ActionExecutionServiceTest.java` first with the test stubs above (use `assertThat(future.get(...)).isInstanceOf(...)`). All tests should COMPILE but FAIL.
2. Run `mvn test -Dtest=ActionExecutionServiceTest` — confirm `Tests run: 9, Failures: 9` (or compile errors).
3. Implement the service.
4. Re-run; confirm `Tests run: 9, Failures: 0`.
5. Commit.

## Commit message template

```
feat(browser): ActionExecutionService with cancel CAS + stop-click routing (P3)

- Mono<ActionResult> execute(session, req) — sends action.execute, awaits matching action.result.
- Mono<Void> cancel(session, msgId, reason) — atomic CAS to win race against result delivery.
- deliverResult / handleStopClicked called by EdgeWebSocketHandler on inbound envelopes.
- Deadline expiry → Failure(DEADLINE_EXCEEDED, retryable=true).
- Session detach mid-flight → Failure(SESSION_DETACHED).
- 9 ActionExecutionServiceTest cases cover normal + 4 race conditions.

Phase 2 Wave 1 — task P3.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 完成时告诉我

跟我说"Codex 07 完成"。我会在 Wave 1 final merge 时跑全套测试 + ArchUnit + audit script。

## 不要做的事

- 不要新增任何 `flatMap` / `Flux.merge` on the action-stream path — F4 (PlanExecutionService, Wave 4) 会强制要求 `concatMap`。这里只做单个 action 执行。
- 不要碰 `vip.mate.browser.edge.action.*`（Wave 0 已固定）。
- 不要碰 `vip.mate.browser.edge.session.*`（Phase 1 已稳定）。
- 不要在 controller 里直接 inject `ActionExecutionService` —— 那是 F1 (ActionPlanner, Wave 4) 的工作。
