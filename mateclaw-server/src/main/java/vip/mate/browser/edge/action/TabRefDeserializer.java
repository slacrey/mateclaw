package vip.mate.browser.edge.action;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

public class TabRefDeserializer extends StdDeserializer<TabRef> {

    protected TabRefDeserializer() {
        super(TabRef.class);
    }

    @Override
    public TabRef deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return switch (p.currentToken()) {
            case VALUE_STRING -> switch (p.getText()) {
                case "main" -> new TabRef.Main();
                case "active" -> new TabRef.Active();
                default -> throw JsonMappingException.from(p,
                        "tab_ref string must be 'main' or 'active', got: " + p.getText());
            };
            case VALUE_NUMBER_INT -> new TabRef.Explicit(p.getLongValue());
            default -> throw JsonMappingException.from(p,
                    "tab_ref must be string 'main'/'active' or integer tab id, got token: " + p.currentToken());
        };
    }
}
