package vip.mate.browser.edge.action;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Identifies which tab an action targets. Three forms:
 *
 * <ul>
 *     <li>{@link Main} - "main" wire literal; SW resolves via TabGroupManager</li>
 *     <li>{@link Active} - "active" wire literal; SW resolves via chrome.tabs.query</li>
 *     <li>{@link Explicit} - bare integer wire form; resolved literally to that tab id</li>
 * </ul>
 *
 * Jackson polymorphism is handled by {@link TabRefSerializer} /
 * {@link TabRefDeserializer} (a discriminated union over JSON value shape:
 * string vs number - neither standard NAME nor DEDUCTION can express that).
 */
@JsonSerialize(using = TabRefSerializer.class)
@JsonDeserialize(using = TabRefDeserializer.class)
public sealed interface TabRef permits TabRef.Main, TabRef.Active, TabRef.Explicit {

    record Main() implements TabRef {}

    record Active() implements TabRef {}

    record Explicit(long tabId) implements TabRef {}
}
