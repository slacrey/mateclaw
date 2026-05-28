package vip.mate.browser.edge.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActionResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successSerialise_carriesOkTrueAndPayload() throws Exception {
        ActionResult result = new ActionResult.Success(
                42,
                new NavigateSuccess("https://example.com/final", 200, "load")
        );

        String json = mapper.writeValueAsString(result);

        assertThat(json)
                .contains("\"ok\":true")
                .contains("\"elapsed_ms\":42")
                .contains("\"final_url\":\"https://example.com/final\"");
    }

    @Test
    void failureSerialise_carriesOkFalseAndCode() throws Exception {
        ActionResult result = new ActionResult.Failure("TIMEOUT_PAGE_LOAD", "page did not load", true);

        String json = mapper.writeValueAsString(result);

        assertThat(json)
                .contains("\"ok\":false")
                .contains("\"code\":\"TIMEOUT_PAGE_LOAD\"")
                .contains("\"message\":\"page did not load\"");
    }

    @Test
    void successDeserialise_dispatchesByOkField() throws Exception {
        // ActionResult uses NAME-on-`ok` (boolean-as-discriminator); the
        // nested `payload` is ActionSuccessPayload which uses NAME-on-`kind`.
        // Both discriminators must be present for round-trip correctness.
        String json = """
            {"ok":true,"elapsed_ms":25,"payload":{"kind":"navigate","final_url":"https://example.com","http_status":200,"load_state":"load"}}
            """;

        ActionResult result = mapper.readValue(json, ActionResult.class);

        assertThat(result).isInstanceOf(ActionResult.Success.class);
        ActionResult.Success success = (ActionResult.Success) result;
        assertThat(success.elapsedMs()).isEqualTo(25);
        assertThat(success.payload()).isEqualTo(new NavigateSuccess("https://example.com", 200, "load"));
    }

    @Test
    void failureRetryableField() throws Exception {
        String json = """
            {"ok":false,"code":"DEADLINE_EXCEEDED","message":"deadline reached","retryable":true}
            """;

        ActionResult result = mapper.readValue(json, ActionResult.class);

        assertThat(result).isEqualTo(new ActionResult.Failure("DEADLINE_EXCEEDED", "deadline reached", true));
    }
}
