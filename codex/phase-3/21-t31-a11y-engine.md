# Codex Task 21 — Phase 3 T3.1：A11yEngine real implementation

> 你（Codex）要把 Phase 2 的 stub `A11yEngine` 替换为真实现 —— 当上游 DomEngine 返回 `Ambiguous` 时，用 parent-role / nearLabel 提示缩窄候选。这是从"找不到"到"找得对"的中间层。

## 项目背景

阅读这些文件先理清上下文：
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/A11yEngine.java` —— Phase 2 stub，目前 always `Miss("stub-phase-2")`
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/engine/DomEngine.java` —— 真实现参考（Phase 2 F3）
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/GroundingDispatcher.java` —— 链 DOM → A11y → Vision；A11y 真实现后这里**不需要改**，dispatcher policy 已经正确
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/domain/GroundingHint.java` —— 你要**扩展** A11yMatch 加 `nearLabel` 可选字段
- `mateclaw-server/src/main/java/vip/mate/browser/orchestrator/domain/PageSnapshot.java` —— `lines()` 提供解析后的 `List<Line>`，每行有 role + refId + name + bbox
- `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/engine/DomEngineTest.java` —— 测试结构 reference

## 关键 P0 不变量：parent-role precedence

测试要求：当同名 role 在多层祖先时，**最近**的匹配胜出。例如 hint `nearLabel="Comments"` 时，page tree 里：
```
main
  article (heading: "Posts")
    button "Like" [ref_3]                  ← 不应该选这个
  article (heading: "Comments")
    button "Like" [ref_5]                  ← 应该选这个
```
即使两个 button 都叫 "Like"，且祖先链都包含 "article"，但 `ref_5` 的最近 article 的 heading 是 "Comments"。

## 你要交付的 3 类改动

### 1. 扩展 `GroundingHint.A11yMatch`

```java
record A11yMatch(String role, Pattern namePattern, String filter, String nearLabel) implements GroundingHint {
    // Existing compact ctor stays; just add nearLabel param + null-permissive.
    public A11yMatch {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role is required");
        if (namePattern == null) throw new IllegalArgumentException("namePattern is required");
        if (filter == null) filter = "interactive";
        // nearLabel may be null — engines that don't honor it ignore it.
    }

    public A11yMatch(String role, Pattern namePattern) {
        this(role, namePattern, "interactive", null);
    }

    public A11yMatch(String role, Pattern namePattern, String filter) {
        this(role, namePattern, filter, null);
    }
}
```

**测试放在新文件** `mateclaw-server/src/test/java/vip/mate/browser/orchestrator/domain/GroundingHintTest.java` **不要碰 DomainTypesTest.java** —— Codex 19 (T3.A iframe) 在并行修改它（加 `frame=N` 解析），双方落地会冲突。新文件覆盖：
- `nearLabel_isOptional_existing2argCtorStillWorks`
- `nearLabel_isOptional_existing3argCtorStillWorks`
- `nearLabel_can_be_null` (4-arg with null)
- `nearLabel_can_be_set` (4-arg with value)
- `a11yMatch_with_nearLabel_preservesAllFields`

### 2. `A11yEngine.java` 真实现

```java
@Component
public class A11yEngine implements GroundingEngine {

    @Override public String name() { return "a11y"; }

    @Override
    public GroundingResult ground(PageSnapshot snapshot, GroundingHint hint) {
        return switch (hint) {
            case GroundingHint.A11yMatch m -> groundByA11yMatch(snapshot, m);
            case GroundingHint.ByRefId r -> new GroundingResult.Miss(
                "a11y engine does not handle ByRefId hints (DOM engine owns those)");
        };
    }

    private GroundingResult groundByA11yMatch(PageSnapshot snapshot, GroundingHint.A11yMatch hint) {
        // Phase 3 A11yEngine intended role:
        //   - DomEngine has just returned Ambiguous with N candidates
        //   - We use ancestor / sibling tree context (which DomEngine ignored)
        //     to narrow.
        // If nearLabel is set, candidate must have an ancestor with a "headinglike"
        // role (heading / section / landmark / article / region) whose name CONTAINS
        // (case-insensitive) the nearLabel string. Among multiple matches, pick the
        // NEAREST ancestor (smallest tree distance) — P0 invariant.
        // If nearLabel is null, fall through to Miss — A11y can't help without a hint.
        // ...
    }
}
```

测试 (8+):
- `nullNearLabel_returnsMiss` —— hint 没有 nearLabel 时直接 Miss（让 dispatcher cascade 到 Vision）
- `singleCandidateMatchingNearLabelAncestor_returnsHit` —— happy path
- `multipleCandidatesAllUnderSameAncestor_returnsAmbiguous` —— nearLabel 无法缩窄时仍可能 Ambiguous，给 Vision 一次
- `nearLabelMatchesCaseInsensitive` —— "comments" matches "Comments"
- `nearestAncestorWins_p0Invariant` —— **the P0 test**：两个 candidate 都在 `article` 下，但内层 article 的 heading 才匹配 nearLabel，必须选内层那个
- `nearLabelOnlyMatchesHeadinglikeRoles` —— "Submit" 在一个 button 文本里不算 ancestor 匹配（只 heading/landmark/section/article/region 算）
- `byRefIdHint_returnsMiss` —— A11y 不处理 byRefId
- `evidenceFieldOnHit_describesNearLabelMatch` —— Hit.evidence 包含 "narrowed by ancestor heading: Comments" 之类

### 3. `GroundingDispatcherTest.java` 加 cascade case

Phase 2 已经测了 DOM→A11y stub→Vision stub。现在 A11y 是真的了，加一个：
- `domAmbiguous_a11yHitViaNearLabel_returnsA11yHit`：DOM 返回 Ambiguous(2 candidates)，A11y 用 nearLabel 缩到 1，返回 Hit。

## TDD 步骤

1. 加 GroundingHint.A11yMatch 的 nearLabel 字段 + 更新 DomainTypesTest case
2. 跑 `mvn test -Dtest=DomainTypesTest` 确认 forward-compat（旧 ctor 仍然工作）
3. 写 A11yEngineTest.java（新文件，~9 cases）
4. 跑 → fail
5. 实现 A11yEngine.java 真逻辑
6. 跑 → 全绿
7. 加 GroundingDispatcherTest.cascade case
8. 整体 `mvn test -Dtest='vip.mate.browser.orchestrator.**'` 不回归

## Commit message template

```
feat(browser): A11yEngine real implementation — narrows Ambiguous by nearLabel ancestor (Phase 3 T3.1 — Codex 21)

Replaces the Phase 2 stub returning Miss("stub-phase-2"). Real impl
walks the candidate list and uses the GroundingHint.A11yMatch.nearLabel
field (new optional field) to filter by nearest ancestor with a
headinglike role (heading | section | landmark | article | region).

P0 invariant: when multiple ancestors at different tree depths
contain a name matching nearLabel, the NEAREST ancestor wins — a
button inside "article: Comments" must beat a button inside "main:
Posts" → "article: Comments" if both contain "Like".

Forward-compat for callers without nearLabel: returns Miss
immediately so the dispatcher cascades to Vision.

ByRefId hints unhandled (return Miss) — DomEngine owns those.

GroundingHint.A11yMatch gains the optional nearLabel field. Existing
ctor signatures preserved.

Tests: 9 A11yEngineTest cases + 1 new GroundingDispatcherTest cascade
case + 2 new DomainTypesTest cases for the nearLabel field.

Phase 3 Wave 3-A2 — task T3.1.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```

## 不要做的事

- 不要碰 `DomEngine` —— Phase 2 已固定
- 不要碰 `GroundingDispatcher` policy 代码 —— dispatcher cascade 已经正确，只加 1 个 test
- 不要把 A11yEngine 写成"也能从零 grounding"的引擎 —— 它的角色 *仅是* disambiguator；空 hint / 无 nearLabel = Miss
- 不要让 VisionEngine 退化为 stub —— 那是 Sub-agent H 在并行做的真实现，本 task 不碰
- **不要碰 `DomainTypesTest.java`** —— Codex 19 (T3.A iframe) 在并行改它加 `frame=N` 解析 case，双方都改会冲突。nearLabel 测试放新文件 `GroundingHintTest.java`
- **不要碰 `PageSnapshot.java`** —— Codex 19 在加 `Line.frameId` 字段；A11yEngine 不需要 frameId，照常用 `lines().get(i).role()/name()/bbox()` 即可
