package vip.mate.browser.edge.action;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

public class TabRefSerializer extends StdSerializer<TabRef> {

    protected TabRefSerializer() {
        super(TabRef.class);
    }

    @Override
    public void serialize(TabRef value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        switch (value) {
            case TabRef.Main main -> gen.writeString("main");
            case TabRef.Active active -> gen.writeString("active");
            case TabRef.Explicit explicit -> gen.writeNumber(explicit.tabId());
        }
    }
}
