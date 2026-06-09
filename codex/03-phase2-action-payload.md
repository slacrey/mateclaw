# Codex Task 03 — Phase 2 头跑：Java sealed ActionPayload 类型族

> 你（Codex）要为 MateClaw Browser Agent **Phase 2** 提前交付一个核心类型族：
> Java sealed `ActionPayload` 接口 + 6 个具体 record 子类型 + 配套的
> `ActionResult` 和 `ActionSuccessPayload` sealed 类型族 + 完整 round-trip 测试。
>
> **这是 Phase 2 计划任务 P2 的实现**，但它可以在 Phase 1 完成前并行写
> —— 因为它在 `vip.mate.browser.edge.action.*` 这个 Phase 1 完全没碰的新子包里。

## 项目背景

MateClaw 的"动作执行"协议（Phase 2 引入）以 Edge protocol 消息
`{ kind: "action.execute", payload: {...} }` 传输。CP（Spring Boot Java）侧
需要把这些消息反序列化为强类型。Phase 1 的 protocol spec 在
`docs/specs/edge-protocol.md`，Phase 2 把 `action.execute` 的 payload 定义为
6 个动作之一（`navigate` / `click` / `type` / `scroll` / `move_mouse` / `wait`），
每个动作有不同字段。

**强类型化要求**：Java sealed 接口 + records，用 Jackson `@JsonTypeInfo(use=DEDUCTION)`
区分子类型（外层 `ActionRequest.kind` 是 discriminator，但 Jackson DEDUCTION
靠每个 record 的字段集合自动判断）。

类似的，`ActionResult` 是 `Success | Failure`，`Success.payload` 是按 kind 决定
的 sealed `ActionSuccessPayload`（每个 kind 一个 record）。这一层让 CP 测试能
做静态类型检查，不再 `Map<String, Object>`。

## 你要交付的 11 个文件

放在 `mateclaw-server/src/main/java/vip/mate/browser/edge/action/` 下面，测试
放在对应的 `src/test/java/...`。

### 主类型

#### `ActionKind.java`（enum）

```java
package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ActionKind {
    NAVIGATE("navigate"),
    CLICK("click"),
    TYPE("type"),
    SCROLL("scroll"),
    MOVE_MOUSE("move_mouse"),
    WAIT("wait");

    private final String wire;
    ActionKind(String wire) { this.wire = wire; }
    @JsonValue public String wire() { return wire; }
    @JsonCreator public static ActionKind fromWire(String s) {
        for (ActionKind k : values()) if (k.wire.equals(s)) return k;
        throw new IllegalArgumentException("unknown ActionKind: " + s);
    }
}
```

#### `ActionPayload.java`（sealed interface + 6 record permits）

接口：
```java
package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
public sealed interface ActionPayload
        permits NavigatePayload, ClickPayload, TypePayload,
                ScrollPayload, MoveMousePayload, WaitPayload {}
```

6 个子 record，每个独立文件：

1. **`NavigatePayload.java`**：`String url`、`String referer`（可 null）、`String waitFor`（枚举字符串 `"load"|"domcontentloaded"|"network_idle"|"none"`，默认 `"load"`）。在 compact constructor 里把 null 的 `waitFor` 默认为 `"load"`，null 的 `url` 抛 IAE。
2. **`ClickPayload.java`**：`double x`、`double y`、`String button`（`"left"|"right"|"middle"`，默认 `"left"`）、`int clickCount`（默认 1，<1 时归一为 1）。Jackson 字段名 `click_count`。
3. **`TypePayload.java`**：`String text`（必填非 null）、`FocusTarget focusTarget`（可 null；如非 null 表示打字前先点这个位置，是嵌套 record `(double x, double y)`）。Jackson 字段名 `focus_target`。
4. **`ScrollPayload.java`**：`String direction`（`"down"|"up"|"left"|"right"`）、`int distancePx`、`Integer segments`（可 null，默认 5）。Jackson 字段名 `distance_px`。
5. **`MoveMousePayload.java`**：`double x`、`double y`、`String profile`（`"natural"|"linear"`，默认 `"natural"`）。
6. **`WaitPayload.java`**：`String strategy`（`"time"|"network_idle"|"load_state"`）+ 三个可选字段 `Long durationMs`、`Long idleThresholdMs`、`String loadState`。Jackson 字段名 `duration_ms` / `idle_threshold_ms` / `load_state`。

每个 record 的 compact constructor 应做基础校验（必填非 null、枚举值在允许列表中），违反抛 `IllegalArgumentException` 而非默默改值（防止 Jackson 反序列化时悄悄默认掉）。

#### `ActionResult.java`（sealed interface）

```java
public sealed interface ActionResult permits ActionResult.Success, ActionResult.Failure {
    record Success(long elapsedMs, ActionSuccessPayload payload) implements ActionResult {}
    record Failure(String code, String message, boolean retryable) implements ActionResult {}
}
```

序列化要求：Success 序列化为 `{"ok":true,"elapsed_ms":N,"payload":{...}}`，Failure 序列化为 `{"ok":false,"code":"...","message":"...","retryable":bool}`。**用 `@JsonTypeInfo` + `@JsonSubTypes` 加 `ok` 作为 discriminator**（不是 DEDUCTION —— ok 字段是显式的）。提示：

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "ok", visible = false)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Success.class, name = "true"),
    @JsonSubTypes.Type(value = Failure.class, name = "false")
})
```

注意每个 Success / Failure record 也要带 `@JsonProperty("ok")` 在虚拟字段上 —— 简单做法是 `@JsonTypeId` 或自定义 serializer。**你需要在这里做工程判断**：哪种 Jackson 实现最简洁正确。把决定记在 commit msg 里。

#### `ActionSuccessPayload.java`（sealed interface）

```java
public sealed interface ActionSuccessPayload
        permits NavigateSuccess, ClickSuccess, TypeSuccess,
                ScrollSuccess, MoveMouseSuccess, WaitSuccess {}
```

6 个 record：
- `NavigateSuccess(String finalUrl, Integer httpStatus, String loadState)` （Jackson `final_url` / `http_status` / `load_state`）
- `ClickSuccess()` （空）
- `TypeSuccess(int charsTyped)` （`chars_typed`）
- `ScrollSuccess()`（空）
- `MoveMouseSuccess(long arrivedAtMs, int waypoints)`（`arrived_at_ms`）
- `WaitSuccess(long waitedMs)`（`waited_ms`）

也用 `DEDUCTION` 区分（字段集合足够区分）。

### 测试（放在 `src/test/java/vip/mate/browser/edge/action/`）

#### `ActionPayloadTest.java`

最少 6 个 round-trip 测试，每个 kind 一个：

```java
@Test
void navigatePayload_roundTrip() throws Exception {
    var nav = new NavigatePayload("https://example.com", "https://referer.example", "network_idle");
    String json = mapper.writeValueAsString(nav);
    assertThat(json).contains("\"url\":\"https://example.com\"")
                    .contains("\"wait_for\":\"network_idle\"");

    NavigatePayload back = mapper.readValue(json, NavigatePayload.class);
    assertThat(back).isEqualTo(nav);
}

@Test
void clickPayload_clickCountDefaultsTo1() throws Exception {
    String json = "{\"x\":10,\"y\":20}";
    ClickPayload back = mapper.readValue(json, ClickPayload.class);
    assertThat(back.clickCount()).isEqualTo(1);
    assertThat(back.button()).isEqualTo("left");
}

@Test
void navigatePayload_nullUrl_throws() {
    assertThatThrownBy(() -> new NavigatePayload(null, null, "load"))
            .isInstanceOf(IllegalArgumentException.class);
}

// ... 一个每 kind 至少 1 个 round-trip + 1 个默认值/校验测试
```

**关键测试**：`abstractInterfaceDispatch` —— 给 `ActionPayload` 反序列化能正确 dispatch 到子类型：

```java
@Test
void abstractInterfaceDispatch() throws Exception {
    String json = """
        {"url":"https://example.com","wait_for":"load"}
        """;
    ActionPayload p = mapper.readValue(json, ActionPayload.class);
    assertThat(p).isInstanceOf(NavigatePayload.class);
    assertThat(((NavigatePayload) p).url()).isEqualTo("https://example.com");
}

@Test
void abstractInterfaceDispatch_click() throws Exception {
    String json = "{\"x\":100,\"y\":200,\"button\":\"right\"}";
    ActionPayload p = mapper.readValue(json, ActionPayload.class);
    assertThat(p).isInstanceOf(ClickPayload.class);
}

@Test
void abstractInterfaceDispatch_ambiguous_throws() {
    // 故意构造两个 kind 都能匹配的 JSON（例如只有 x, y —— click 和 move_mouse 都有这俩字段）
    String json = "{\"x\":1,\"y\":2}";
    // Jackson DEDUCTION 在歧义时会抛 InvalidDefinitionException 或类似
    // 测试要 expect 它会**确定性**选其中一个 OR 抛错；记下实际行为
    // 你可能需要给某些 record 加 @JsonInclude 区分必填字段
}
```

#### `ActionResultTest.java`

最少 4 个测试：
- `successSerialise_carriesOkTrueAndPayload`
- `failureSerialise_carriesOkFalseAndCode`
- `successDeserialise_dispatchesByOkField`
- `failureRetryableField`

测试 `ActionResult.Failure` 的 `code` 字段：示例值用 `"TIMEOUT_PAGE_LOAD"` /
`"DEADLINE_EXCEEDED"` / `"CANCELLED"` / `"GROUNDING_AMBIGUOUS"` / `"NO_TARGET_TAB"`。

#### `ActionSuccessPayloadTest.java`

每 kind 一个 round-trip。重点验证 `NavigateSuccess.finalUrl` 序列化为
`"final_url"`，`MoveMouseSuccess.arrivedAtMs` 为 `"arrived_at_ms"`。

## Java 工程环境（让你不踩坑）

- JDK 21，启用 records + sealed types
- Spring Boot 3.5.x 用 Jackson 2.18.x
- 用 Lombok 仅在测试侧 `@Slf4j` 之类，主类型用纯 record（不要 `@Data`）
- 测试用 JUnit 5 + AssertJ，**不要**用 Hamcrest
- Maven module 是 `mateclaw-server`，从 root POM 继承
- 包名严格 `vip.mate.browser.edge.action.payloads` 也行，但建议 **所有
  payload 子类型直接放在 `action.` 下**（不再嵌一层 `payloads.`），让 import
  浅。下面交付清单按 `action.` 平铺。

## 验收

- [ ] 11 个新文件齐全（1 enum + 1 sealed interface + 6 payload records + 1
  sealed ActionResult + 1 sealed ActionSuccessPayload + 6 success records；
  注：ActionResult / ActionSuccessPayload 可以放同一文件内）
- [ ] **mvn test 全绿**（你需要在 Codex 沙盒里启动一个 Maven 干跑；如果沙盒无
  Maven 就把所有测试用 `javac` + 一个简单 Jackson 调用替代，证明类型可编译 +
  序列化正常）
- [ ] DEDUCTION 不歧义：你测过 `abstractInterfaceDispatch` 几种典型 JSON 都能
  正确路由
- [ ] 每个 record 的 compact constructor 都有校验
- [ ] commit msg 里**说明**你对 ActionResult discriminator 的实现选择（NAME +
  JsonSubTypes vs 自定义 serializer）

## 交付格式

按文件清单顺序输出，每文件用 `========== FILE: <path> ==========` 分隔。
最后是 mvn test 输出（或如果沙盒无 mvn，用 javac + 一个 main 函数跑序列化 demo
的输出代替）。再下面是 commit msg：

```
feat(browser): add Phase 2 ActionPayload sealed type family

Sealed ActionPayload interface + 6 record permits (Navigate/Click/Type
/Scroll/MoveMouse/Wait) with Jackson DEDUCTION-based polymorphism.
Each record has a compact constructor doing required-field validation
to keep the protocol contract honest at boundary.

Sealed ActionResult (Success/Failure) and ActionSuccessPayload type
family follow the same pattern. ActionResult uses NAME-based
discrimination on the "ok" field (chose this over a custom serializer
because the JSON shape requires the field to appear; DEDUCTION
fails when one variant has zero fields).

Round-trip tests cover all 6 kinds plus a polymorphic dispatch test
and a validation-rejection test.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```
