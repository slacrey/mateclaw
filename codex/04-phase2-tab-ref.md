# Codex Task 04 — Phase 2 头跑：Java sealed TabRef + Jackson 自定义 serde

> 你（Codex）要为 MateClaw Browser Agent **Phase 2** 提前交付 `TabRef` sealed
> 类型 + 自定义 Jackson 序列化器和反序列化器 + 测试。
>
> **这是 Phase 2 计划任务 P2 的实现**，可以在 Phase 1 完成前并行写。它在
> `vip.mate.browser.edge.action.TabRef` 这个 Phase 1 完全没碰的位置。

## 项目背景

Phase 2 引入的 `action.execute`、`indicator.*`、`a11y.snapshot.request` 三类
envelope 都需要携带一个字段 `tab_ref` 告诉 Native Host 这条命令应该作用在哪
个 Chrome tab 上。`tab_ref` 的合法值有三种形态：

| JSON 形态 | 语义 |
|---|---|
| `"main"`（字符串） | 由 SW 端的 `TabGroupManager.getMainTabId(subject)` 解析成具体 tab id |
| `"active"`（字符串） | 由 SW 端的 `chrome.tabs.query({active:true, lastFocusedWindow:true})` 解析 |
| `42`（整数） | 显式 Chrome tab id（用于测试和未来多 tab orchestration） |

CP 侧（Java）拿到 envelope 后需要把这个字段 **反序列化为强类型**，再原样
转发给 Native Host（不要解析成具体数字 —— 解析是 SW 的职责）。强类型化的
意义：planner 在不同 step 之间传 `TabRef` 时，类型系统强制每个 step 都带
正确的 `TabRef`，不可能漏。

## 你要交付的 6 个文件

放在 `mateclaw-server/src/main/java/vip/mate/browser/edge/action/` 下。测试
在对应 `src/test/java/...`。

### 1. `TabRef.java` —— sealed interface + 3 个 record permits

```java
package vip.mate.browser.edge.action;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Identifies which tab an action targets. Three forms:
 *
 *   - {@link Main}      — "main" wire literal; SW resolves via TabGroupManager
 *   - {@link Active}    — "active" wire literal; SW resolves via chrome.tabs.query
 *   - {@link Explicit}  — bare integer wire form; resolved literally to that tab id
 *
 * Jackson polymorphism is handled by {@link TabRefSerializer} /
 * {@link TabRefDeserializer} (a discriminated union over JSON value shape:
 * string vs number — neither standard NAME nor DEDUCTION can express that).
 */
@JsonSerialize(using = TabRefSerializer.class)
@JsonDeserialize(using = TabRefDeserializer.class)
public sealed interface TabRef permits TabRef.Main, TabRef.Active, TabRef.Explicit {
    record Main() implements TabRef {}
    record Active() implements TabRef {}
    record Explicit(long tabId) implements TabRef {}
}
```

### 2. `TabRefSerializer.java`

```java
package vip.mate.browser.edge.action;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

public class TabRefSerializer extends StdSerializer<TabRef> {

    protected TabRefSerializer() { super(TabRef.class); }

    @Override
    public void serialize(TabRef value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        switch (value) {
            case TabRef.Main m       -> gen.writeString("main");
            case TabRef.Active a     -> gen.writeString("active");
            case TabRef.Explicit e   -> gen.writeNumber(e.tabId());
        }
    }
}
```

### 3. `TabRefDeserializer.java`

```java
package vip.mate.browser.edge.action;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

public class TabRefDeserializer extends StdDeserializer<TabRef> {

    protected TabRefDeserializer() { super(TabRef.class); }

    @Override
    public TabRef deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return switch (p.currentToken()) {
            case VALUE_STRING -> switch (p.getText()) {
                case "main"   -> new TabRef.Main();
                case "active" -> new TabRef.Active();
                default       -> throw JsonMappingException.from(p,
                        "tab_ref string must be 'main' or 'active', got: " + p.getText());
            };
            case VALUE_NUMBER_INT -> new TabRef.Explicit(p.getLongValue());
            default -> throw JsonMappingException.from(p,
                    "tab_ref must be string 'main'/'active' or integer tab id, got token: " + p.currentToken());
        };
    }
}
```

### 4. `TabRefTest.java`（round-trip + boundary + container 测试）

放 `mateclaw-server/src/test/java/vip/mate/browser/edge/action/TabRefTest.java`：

```java
package vip.mate.browser.edge.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TabRefTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── serialise ───────────────────────────────────────────

    @Test
    void main_serialisesAsStringMain() throws Exception {
        String json = mapper.writeValueAsString(new TabRef.Main());
        assertThat(json).isEqualTo("\"main\"");
    }

    @Test
    void active_serialisesAsStringActive() throws Exception {
        assertThat(mapper.writeValueAsString(new TabRef.Active())).isEqualTo("\"active\"");
    }

    @Test
    void explicit_serialisesAsBareInteger() throws Exception {
        assertThat(mapper.writeValueAsString(new TabRef.Explicit(42))).isEqualTo("42");
    }

    @Test
    void explicit_largeTabId_serialisesAsLong() throws Exception {
        // Chrome tab ids fit in int but the API is conservative — Long is fine.
        assertThat(mapper.writeValueAsString(new TabRef.Explicit(2_147_483_648L)))
                .isEqualTo("2147483648");
    }

    // ── deserialise ─────────────────────────────────────────

    @Test
    void deserialise_main() throws Exception {
        assertThat(mapper.readValue("\"main\"", TabRef.class)).isEqualTo(new TabRef.Main());
    }

    @Test
    void deserialise_active() throws Exception {
        assertThat(mapper.readValue("\"active\"", TabRef.class)).isEqualTo(new TabRef.Active());
    }

    @Test
    void deserialise_explicit() throws Exception {
        TabRef ref = mapper.readValue("42", TabRef.class);
        assertThat(ref).isEqualTo(new TabRef.Explicit(42L));
    }

    @Test
    void deserialise_unknownString_throws() {
        assertThatThrownBy(() -> mapper.readValue("\"nope\"", TabRef.class))
                .hasMessageContaining("tab_ref string must be 'main' or 'active'");
    }

    @Test
    void deserialise_floatNumber_throws() {
        // Floats are not valid tab ids; the deserializer should reject.
        assertThatThrownBy(() -> mapper.readValue("42.7", TabRef.class))
                .hasMessageContaining("tab_ref must be string");
    }

    @Test
    void deserialise_boolean_throws() {
        assertThatThrownBy(() -> mapper.readValue("true", TabRef.class))
                .hasMessageContaining("tab_ref must be string");
    }

    @Test
    void deserialise_objectShape_throws() {
        assertThatThrownBy(() -> mapper.readValue("{\"kind\":\"main\"}", TabRef.class))
                .hasMessageContaining("tab_ref must be string");
    }

    // ── pattern matching dispatch (Java 21 switch) ─────────

    @Test
    void switchExhaustivityForcesAllArms() {
        // If a new permits subtype is ever added, this switch FAILS to compile —
        // a static invariant we want to lean on.
        TabRef[] all = { new TabRef.Main(), new TabRef.Active(), new TabRef.Explicit(7) };
        for (TabRef ref : all) {
            String label = switch (ref) {
                case TabRef.Main m     -> "main";
                case TabRef.Active a   -> "active";
                case TabRef.Explicit e -> "explicit:" + e.tabId();
            };
            assertThat(label).isNotBlank();
        }
    }

    // ── as embedded field in a wrapper envelope ────────────

    record EnvelopeWrapper(TabRef tab_ref, String kind) {}

    @Test
    void embeddedInEnvelope_roundTrips_forMain() throws Exception {
        var env = new EnvelopeWrapper(new TabRef.Main(), "click");
        String json = mapper.writeValueAsString(env);
        assertThat(json).contains("\"tab_ref\":\"main\"")
                        .contains("\"kind\":\"click\"");
        EnvelopeWrapper back = mapper.readValue(json, EnvelopeWrapper.class);
        assertThat(back).isEqualTo(env);
    }

    @Test
    void embeddedInEnvelope_roundTrips_forExplicit() throws Exception {
        var env = new EnvelopeWrapper(new TabRef.Explicit(99), "navigate");
        String json = mapper.writeValueAsString(env);
        assertThat(json).contains("\"tab_ref\":99");
        EnvelopeWrapper back = mapper.readValue(json, EnvelopeWrapper.class);
        assertThat(back).isEqualTo(env);
    }
}
```

至少 14 个测试都应通过。

### 5. （可选额外）`TabRefFactory.java`

一个静态工厂类，方便 planner 代码读：

```java
public final class TabRefFactory {
    private TabRefFactory() {}
    public static TabRef main() { return new TabRef.Main(); }
    public static TabRef active() { return new TabRef.Active(); }
    public static TabRef explicit(long tabId) { return new TabRef.Explicit(tabId); }
}
```

如果你觉得记 `new TabRef.Main()` 比 `TabRefFactory.main()` 简洁，工厂可以跳过 —— 这是品味判断。**注明你的判断**在 commit msg。

## 工程注意

- JDK 21 → switch over sealed 是 GA 特性
- Jackson 2.18.x（Spring Boot 3.5.14 自带）
- Spring Boot 默认 `ObjectMapper` 已经能用，不需要全局 Module 注册（`@JsonSerialize` / `@JsonDeserialize` 注解会被 Jackson 自动识别）
- 不要写 `@Component` —— 这是纯类型，不是 Spring bean
- 测试用 JUnit 5 + AssertJ

## 验收

- [ ] 至少 4 个新文件（TabRef、Serializer、Deserializer、Test）
- [ ] **mvn test 全绿**（沙盒里如无 Maven，至少证明类型编译 + 用一段 main 跑 round-trip demo）
- [ ] Boundary 测试全部覆盖：float 拒绝、boolean 拒绝、object 拒绝、未知字符串拒绝
- [ ] 嵌入 wrapper 的 round-trip 测试通过（确认 Jackson 在字段级别也正确）
- [ ] `switch (ref)` 模式匹配测试存在 —— 这是 sealed types 的核心收益保护

## 交付格式

```
========== FILE: mateclaw-server/src/main/java/vip/mate/browser/edge/action/TabRef.java ==========
<内容>
========== FILE: mateclaw-server/src/main/java/vip/mate/browser/edge/action/TabRefSerializer.java ==========
<内容>
========== FILE: mateclaw-server/src/main/java/vip/mate/browser/edge/action/TabRefDeserializer.java ==========
<内容>
========== FILE: mateclaw-server/src/test/java/vip/mate/browser/edge/action/TabRefTest.java ==========
<内容>
========== (OPTIONAL) FILE: mateclaw-server/src/main/java/vip/mate/browser/edge/action/TabRefFactory.java ==========
<内容 or 说明跳过>
========== BUILD/TEST OUTPUT ==========
<mvn test 或等效证据>
========== COMMIT MSG ==========
feat(browser): add Phase 2 TabRef sealed type with custom JSON serde

Sealed TabRef interface with three record permits (Main / Active /
Explicit). Wire form is a discriminated union over JSON value SHAPE
(string vs number), which Jackson cannot express through standard
NAME/DEDUCTION discriminators — implemented with custom
TabRefSerializer / TabRefDeserializer wired via @JsonSerialize /
@JsonDeserialize on the sealed interface.

[Note about TabRefFactory: kept / skipped — your reason here]

14 round-trip + boundary tests pass: each subtype serialises / deserialises,
unknown strings / floats / booleans / objects all reject with typed
JsonMappingException, embedding in a wrapper record works at the field
level, and Java 21 switch-over-sealed is exercised to lock in
exhaustivity-on-future-changes.

Co-Authored-By: Codex GPT-5 (parallel) <noreply@openai.com>
```
