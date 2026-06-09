# Codex Task 17 — Phase 2 F2 + F3：GroundingDispatcher + DomEngine

> 你（Codex）要**一次性交付两个紧耦合的组件**：
> - **F3 DomEngine** —— 第一个 `GroundingEngine` 实现：pattern-match `(role, name)` against A11y tree lines from PageSnapshot
> - **F2 GroundingDispatcher** —— 把 DOM → A11y → Vision 三个引擎按优先级链起来；带 snapshot 刷新钩子
>
> Phase 2: **A11y 和 Vision 引擎留 stub**（返回 `Miss("stub-phase-2")`），DOM 是真实现。

## 项目背景

阅读这些文件先理清上下文（Wave 4-0 已落地）：
- `domain/GroundingEngine.java` —— interface { String name(); GroundingResult ground(snapshot, hint); }
- `domain/GroundingResult.java` —— sealed `Hit | Ambiguous | Miss`
- `domain/GroundingHint.java` —— sealed `A11yMatch(role, namePattern, filter) | ByRefId(refId, filter)`
- `domain/PageSnapshot.java` —— 有 `.lines()` 已经解析出 `List<Line>` where `Line(role, refId, name, bbox)`
- `domain/BBox.java`
- F5 PageSnapshotService（并行做的）—— interface `Mono<PageSnapshot> request(BrowserSession, TabRef, String filter)`

## 你要交付的 6 个文件

### 1. `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/DomEngine.java`

```java
package vip.mate.browser.orchestrator.engine;

@Component
public class DomEngine implements GroundingEngine {

    @Override public String name() { return "dom"; }

    @Override
    public GroundingResult ground(PageSnapshot snapshot, GroundingHint hint) {
        return switch (hint) {
            case GroundingHint.A11yMatch m -> groundByA11yMatch(snapshot, m);
            case GroundingHint.ByRefId r -> groundByRefId(snapshot, r);
        };
    }

    private GroundingResult groundByA11yMatch(PageSnapshot snapshot, GroundingHint.A11yMatch hint) {
        // 1. Filter snapshot.lines() by role (case-insensitive).
        // 2. Among those, filter by namePattern.matcher(line.name()).matches().
        // 3. If 0 → Miss("no role+name match for role=X name=/Y/")
        // 4. If 1 → Hit(GroundedTarget(line.bbox(), line.refId()), "role+name match: <evidence>")
        // 5. If >=2 → Ambiguous(candidates, "N elements with role=X matching /Y/")
    }

    private GroundingResult groundByRefId(PageSnapshot snapshot, GroundingHint.ByRefId hint) {
        // 1. Find the single line with line.refId().equals(hint.refId()).
        // 2. Found → Hit(GroundedTarget(line.bbox(), line.refId()), "ref " + refId)
        // 3. Not found → Miss("ref " + refId + " not in snapshot")
    }
}
```

### 2. `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/A11yEngine.java` (Phase-2 stub)

```java
@Component
public class A11yEngine implements GroundingEngine {
    @Override public String name() { return "a11y"; }
    @Override public GroundingResult ground(PageSnapshot s, GroundingHint h) {
        return new GroundingResult.Miss("stub-phase-2: A11y engine not yet implemented");
    }
}
```

### 3. `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/VisionEngine.java` (Phase-2 stub)

Same shape as A11yEngine, `name() = "vision"`.

### 4. `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/GroundingDispatcher.java`

```java
@Service
@RequiredArgsConstructor
public class GroundingDispatcher {

    private final DomEngine dom;
    private final A11yEngine a11y;
    private final VisionEngine vision;
    private final PageSnapshotService snapshotService;

    public GroundingResult ground(BrowserSession session, TabRef tabRef, GroundingHint hint) {
        // 1. Get a fresh snapshot via PageSnapshotService.request(session, tabRef, hint.filter())
        //    — block on the Mono for Phase 2 (P3 will go fully reactive).
        // 2. Try engines in order: dom → a11y → vision.
        // 3. Resolution policy:
        //    - Hit       → return immediately
        //    - Ambiguous → remember the FIRST Ambiguous; try next engine; if next engine Hits, return that
        //    - Miss      → try next engine
        // 4. If we exit the loop with no Hit and we saw an Ambiguous, return that Ambiguous.
        // 5. Otherwise return Miss("no-engine-hit").
    }
}
```

### 5. `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/engine/DomEngineTest.java`

Required tests (8+):

```java
@Test void hitWhenExactlyOneA11yLineMatchesRoleAndName() { /* spec 中已给 */ }

@Test void ambiguousWhenTwoLinesMatchSameRoleAndName() {
    // Two "Button[ref=ref_1]: Submit ..." and "Button[ref=ref_3]: Submit ..."
}

@Test void missWhenPatternDoesNotMatch() { /* */ }

@Test void missWhenRoleDoesNotMatch_evenIfNameDoes() {
    // Hint: role=button, but the snapshot only has Link with that name
}

@Test void refIdHit_whenRefIdHintProvidedAndPresent() { /* */ }

@Test void refIdMiss_whenRefIdHintProvidedButAbsent() { /* */ }

@Test void roleMatchIsCaseInsensitive() {
    // Hint role="Button" should match snapshot lines with role="button"
}

@Test void namePatternUsesRegex_notSubstring() {
    // Pattern "^Submit$" should match "Submit" but NOT "Submit form"
}

@Test void evidenceFieldOnHit_isInformative() {
    // Hit.evidence() contains the role + matched name + ref id
}
```

### 6. `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/GroundingDispatcherTest.java`

Required tests (~7), use Mockito for the three engines + PageSnapshotService:

```java
@Test void domHit_returnsHit_doesNotInvokeA11yOrVision() {
    // domEngine returns Hit → assert a11yEngine.ground was never called
}

@Test void domMissAllStubs_returnsMiss() { /* */ }

@Test void domAmbiguous_a11yStubMiss_visionStubMiss_returnsTheFirstAmbiguous() { /* */ }

@Test void domAmbiguous_a11yHit_returnsA11yHit_disambiguation() {
    // when(a11y.ground(any(), any())).thenReturn(Hit(t1, "narrowed"));
    // (Even though A11y is a stub returning Miss in production, the test
    //  passes a different mocked engine so it CAN Hit. Verifies dispatcher
    //  policy correctly cascades to disambiguate.)
}

@Test void domMiss_a11yAmbiguous_visionHit_returnsVisionHit() { /* cascade through both fallbacks */ }

@Test void refreshesSnapshotBeforeGround_viaPageSnapshotService() {
    // verify(snapshotService).request(eq(session), eq(new TabRef.Main()), eq(hint.filter()))
}

@Test void firstAmbiguousIsRemembered_evenIfLaterEnginesAlsoAmbiguous() {
    // Both DOM and A11y return Ambiguous with different candidate lists.
    // Returned ambiguous should be the DOM one (the first observed).
}
```

## TDD 步骤

1. 写 DomEngineTest.java + GroundingDispatcherTest.java → fail
2. 跑 → fail
3. 实现 DomEngine + A11yEngine + VisionEngine + GroundingDispatcher
4. 跑 → 全绿
5. Commit (one commit for all 6 files)

## Commit message template

```
feat(browser): GroundingDispatcher + DomEngine (F2 + F3)

DOM → A11y → Vision chain with Ambiguous-then-disambiguate semantics.
A11y + Vision are Phase-2 stubs returning Miss("stub-phase-2");
Phase 3 SOP work fills them in.

Resolution policy:
  - First Hit wins immediately
  - Ambiguous remembered; try next engine; if next Hits, return Hit
  - Miss → try next engine
  - All Miss → Miss("no-engine-hit")
  - All-or-some Ambiguous, no Hit → first Ambiguous returned

DomEngine pattern-matches (role case-insensitive, name regex) against
PageSnapshot.lines(). ByRefId hint does a direct ref_N lookup.

Dispatcher invokes PageSnapshotService.request() per ground() call —
the snapshot is always fresh-enough at engine entry (FRESH or SUSPECT,
never STALE; F5 owns the refresh).

15 vitest cases across DomEngineTest (9) + GroundingDispatcherTest (6).

Phase 2 Wave 4 — tasks F2 + F3.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要把 Vision 写成真实现 —— stub-only for Phase 2.
- 不要碰 PageSnapshotService —— it's a port; consume via `snapshotService.request(...)` only.
- 不要碰 `domain/*` —— Wave 4-0 已固定。
- 不要让 DomEngine retain state —— engines are stateless; all state lives in PageSnapshot.
