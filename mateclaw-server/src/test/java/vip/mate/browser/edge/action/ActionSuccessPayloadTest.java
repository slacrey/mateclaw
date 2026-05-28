package vip.mate.browser.edge.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActionSuccessPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void navigateSuccess_roundTrip() throws Exception {
        var success = new NavigateSuccess("https://example.com/final", 204, "network_idle");

        String json = mapper.writeValueAsString(success);
        NavigateSuccess back = mapper.readValue(json, NavigateSuccess.class);

        assertThat(json)
                .contains("\"final_url\":\"https://example.com/final\"")
                .contains("\"http_status\":204")
                .contains("\"load_state\":\"network_idle\"");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void clickSuccess_roundTrip() throws Exception {
        var success = new ClickSuccess();

        String json = mapper.writeValueAsString(success);
        ClickSuccess back = mapper.readValue(json, ClickSuccess.class);

        assertThat(json).isEqualTo("{}");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void typeSuccess_roundTrip() throws Exception {
        var success = new TypeSuccess(5);

        String json = mapper.writeValueAsString(success);
        TypeSuccess back = mapper.readValue(json, TypeSuccess.class);

        assertThat(json).contains("\"chars_typed\":5");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void scrollSuccess_roundTrip() throws Exception {
        var success = new ScrollSuccess();

        String json = mapper.writeValueAsString(success);
        ScrollSuccess back = mapper.readValue(json, ScrollSuccess.class);

        assertThat(json).isEqualTo("{}");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void moveMouseSuccess_roundTrip() throws Exception {
        var success = new MoveMouseSuccess(123, 9);

        String json = mapper.writeValueAsString(success);
        MoveMouseSuccess back = mapper.readValue(json, MoveMouseSuccess.class);

        assertThat(json)
                .contains("\"arrived_at_ms\":123")
                .contains("\"waypoints\":9");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void waitSuccess_roundTrip() throws Exception {
        var success = new WaitSuccess(750);

        String json = mapper.writeValueAsString(success);
        WaitSuccess back = mapper.readValue(json, WaitSuccess.class);

        assertThat(json).contains("\"waited_ms\":750");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void abstractInterfaceDispatch_byKindDiscriminator() throws Exception {
        // NAME-based dispatch: the "kind" property is the source of truth.
        // The empty ClickSuccess and ScrollSuccess records (no fields) are
        // only distinguishable via this discriminator — that's why this
        // package abandoned DEDUCTION.
        String moveJson = "{\"kind\":\"move_mouse\",\"arrived_at_ms\":123,\"waypoints\":9}";
        ActionSuccessPayload moveBack = mapper.readValue(moveJson, ActionSuccessPayload.class);
        assertThat(moveBack).isEqualTo(new MoveMouseSuccess(123, 9));

        String clickJson = "{\"kind\":\"click\"}";
        ActionSuccessPayload clickBack = mapper.readValue(clickJson, ActionSuccessPayload.class);
        assertThat(clickBack).isInstanceOf(ClickSuccess.class);

        String scrollJson = "{\"kind\":\"scroll\"}";
        ActionSuccessPayload scrollBack = mapper.readValue(scrollJson, ActionSuccessPayload.class);
        assertThat(scrollBack).isInstanceOf(ScrollSuccess.class);
    }
}
