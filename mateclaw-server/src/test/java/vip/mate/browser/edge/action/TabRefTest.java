package vip.mate.browser.edge.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TabRefTest {

    private final ObjectMapper mapper = new ObjectMapper();

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
        assertThat(mapper.writeValueAsString(new TabRef.Explicit(2_147_483_648L)))
                .isEqualTo("2147483648");
    }

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

    @Test
    void switchExhaustivityForcesAllArms() {
        TabRef[] all = {new TabRef.Main(), new TabRef.Active(), new TabRef.Explicit(7)};
        for (TabRef ref : all) {
            String label = switch (ref) {
                case TabRef.Main main -> "main";
                case TabRef.Active active -> "active";
                case TabRef.Explicit explicit -> "explicit:" + explicit.tabId();
            };
            assertThat(label).isNotBlank();
        }
    }

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
