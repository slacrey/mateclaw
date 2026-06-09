package vip.mate.browser.edge.action;

import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void navigatePayload_roundTrip() throws Exception {
        var nav = new NavigatePayload("https://example.com", "https://referer.example", "network_idle");

        String json = mapper.writeValueAsString(nav);
        NavigatePayload back = mapper.readValue(json, NavigatePayload.class);

        assertThat(json)
                .contains("\"url\":\"https://example.com\"")
                .contains("\"referer\":\"https://referer.example\"")
                .contains("\"wait_for\":\"network_idle\"");
        assertThat(back).isEqualTo(nav);
    }

    @Test
    void clickPayload_roundTrip() throws Exception {
        var click = new ClickPayload(10, 20, "middle", 2);

        String json = mapper.writeValueAsString(click);
        ClickPayload back = mapper.readValue(json, ClickPayload.class);

        assertThat(json).contains("\"click_count\":2");
        assertThat(back).isEqualTo(click);
    }

    @Test
    void typePayload_roundTrip() throws Exception {
        var type = new TypePayload("hello", new TypePayload.FocusTarget(4, 8));

        String json = mapper.writeValueAsString(type);
        TypePayload back = mapper.readValue(json, TypePayload.class);

        assertThat(json).contains("\"focus_target\":{\"x\":4.0,\"y\":8.0}");
        assertThat(back).isEqualTo(type);
    }

    @Test
    void pressKeyPayload_roundTrip() throws Exception {
        var press = new PressKeyPayload("x");

        String json = mapper.writeValueAsString(press);
        PressKeyPayload back = mapper.readValue(json, PressKeyPayload.class);

        assertThat(json).contains("\"key\":\"x\"");
        assertThat(back).isEqualTo(press);
    }

    @Test
    void scrollPayload_roundTrip() throws Exception {
        var scroll = new ScrollPayload("down", 600, 3, 980.0, 360.0);

        String json = mapper.writeValueAsString(scroll);
        ScrollPayload back = mapper.readValue(json, ScrollPayload.class);

        assertThat(json)
                .contains("\"distance_px\":600")
                .contains("\"x\":980.0")
                .contains("\"y\":360.0");
        assertThat(back).isEqualTo(scroll);
    }

    @Test
    void scrollRegionPayload_roundTrip() throws Exception {
        var scroll = new ScrollRegionPayload(
                "douyin.comments",
                "down",
                600,
                new ScrollRegionPayload.StopWhen("edge", null, null),
                4);

        String json = mapper.writeValueAsString(scroll);
        ScrollRegionPayload back = mapper.readValue(json, ScrollRegionPayload.class);

        assertThat(json)
                .contains("\"regionKey\":\"douyin.comments\"")
                .contains("\"amount\":600.0")
                .contains("\"stopWhen\":{\"type\":\"edge\"");
        assertThat(back).isEqualTo(scroll);
    }

    @Test
    void extractRegionPayload_roundTrip() throws Exception {
        var extract = new ExtractRegionPayload("douyin.comments", 25);

        String json = mapper.writeValueAsString(extract);
        ExtractRegionPayload back = mapper.readValue(json, ExtractRegionPayload.class);

        assertThat(json)
                .contains("\"regionKey\":\"douyin.comments\"")
                .contains("\"maxItems\":25");
        assertThat(back).isEqualTo(extract);
    }

    @Test
    void detectRegionPayload_roundTrip() throws Exception {
        var detect = new DetectRegionPayload("douyin.comments", "dom");

        String json = mapper.writeValueAsString(detect);
        DetectRegionPayload back = mapper.readValue(json, DetectRegionPayload.class);

        assertThat(json)
                .contains("\"regionKey\":\"douyin.comments\"")
                .contains("\"strategy\":\"dom\"");
        assertThat(back).isEqualTo(detect);
    }

    @Test
    void moveMousePayload_roundTrip() throws Exception {
        var move = new MoveMousePayload(30, 40, "linear");

        String json = mapper.writeValueAsString(move);
        MoveMousePayload back = mapper.readValue(json, MoveMousePayload.class);

        assertThat(json).contains("\"profile\":\"linear\"");
        assertThat(back).isEqualTo(move);
    }

    @Test
    void waitPayload_roundTrip() throws Exception {
        var wait = new WaitPayload("network_idle", null, 500L, null);

        String json = mapper.writeValueAsString(wait);
        WaitPayload back = mapper.readValue(json, WaitPayload.class);

        assertThat(json).contains("\"idle_threshold_ms\":500");
        assertThat(back).isEqualTo(wait);
    }

    @Test
    void douyinCommentNetworkPayload_roundTrip() throws Exception {
        var payload = new DouyinCommentNetworkPayload("start", 8, 262144, 45000);

        String json = mapper.writeValueAsString(payload);
        DouyinCommentNetworkPayload back = mapper.readValue(json, DouyinCommentNetworkPayload.class);

        assertThat(json)
                .contains("\"op\":\"start\"")
                .contains("\"maxPages\":8")
                .contains("\"maxBodyBytes\":262144")
                .contains("\"ttlMs\":45000");
        assertThat(back).isEqualTo(payload);
    }

    @Test
    void clickPayload_clickCountDefaultsTo1() throws Exception {
        String json = "{\"x\":10,\"y\":20}";

        ClickPayload back = mapper.readValue(json, ClickPayload.class);

        assertThat(back.clickCount()).isEqualTo(1);
        assertThat(back.button()).isEqualTo("left");
    }

    @Test
    void navigatePayload_waitForDefaultsToLoad() throws Exception {
        String json = "{\"url\":\"https://example.com\"}";

        NavigatePayload back = mapper.readValue(json, NavigatePayload.class);

        assertThat(back.waitFor()).isEqualTo("load");
    }

    @Test
    void scrollPayload_segmentsDefaultsTo5() throws Exception {
        String json = "{\"direction\":\"down\",\"distance_px\":120}";

        ScrollPayload back = mapper.readValue(json, ScrollPayload.class);

        assertThat(back.segments()).isEqualTo(5);
    }

    @Test
    void moveMousePayload_profileDefaultsToNatural() throws Exception {
        String json = "{\"x\":10,\"y\":20}";

        MoveMousePayload back = mapper.readValue(json, MoveMousePayload.class);

        assertThat(back.profile()).isEqualTo("natural");
    }

    @Test
    void navigatePayload_nullUrl_throws() {
        assertThatThrownBy(() -> new NavigatePayload(null, null, "load"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typePayload_nullText_throws() {
        assertThatThrownBy(() -> new TypePayload(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pressKeyPayload_blankKey_throws() {
        assertThatThrownBy(() -> new PressKeyPayload(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void waitPayload_invalidStrategy_throws() {
        assertThatThrownBy(() -> new WaitPayload("paint", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abstractInterfaceDispatch_navigate() throws Exception {
        String json = """
            {"kind":"navigate","url":"https://example.com","wait_for":"load"}
            """;

        ActionPayload p = mapper.readValue(json, ActionPayload.class);

        assertThat(p).isInstanceOf(NavigatePayload.class);
        assertThat(((NavigatePayload) p).url()).isEqualTo("https://example.com");
    }

    @Test
    void abstractInterfaceDispatch_click() throws Exception {
        String json = "{\"kind\":\"click\",\"x\":100,\"y\":200,\"button\":\"right\"}";

        ActionPayload p = mapper.readValue(json, ActionPayload.class);

        assertThat(p).isInstanceOf(ClickPayload.class);
    }

    @Test
    void abstractInterfaceDispatch_moveMouse() throws Exception {
        String json = "{\"kind\":\"move_mouse\",\"x\":100,\"y\":200,\"profile\":\"linear\"}";

        ActionPayload p = mapper.readValue(json, ActionPayload.class);

        assertThat(p).isInstanceOf(MoveMousePayload.class);
    }

    @Test
    void abstractInterfaceDispatch_pressKey() throws Exception {
        String json = "{\"kind\":\"press_key\",\"key\":\"x\"}";

        ActionPayload p = mapper.readValue(json, ActionPayload.class);

        assertThat(p).isInstanceOf(PressKeyPayload.class);
        assertThat(((PressKeyPayload) p).key()).isEqualTo("x");
    }

    @Test
    void abstractInterfaceDispatch_scrollRegion() throws Exception {
        String json = """
            {"kind":"scroll_region","regionKey":"douyin.comments","direction":"down","amount":600}
            """;

        ActionPayload p = mapper.readValue(json, ActionPayload.class);

        assertThat(p).isInstanceOf(ScrollRegionPayload.class);
        assertThat(((ScrollRegionPayload) p).regionKey()).isEqualTo("douyin.comments");
    }

    @Test
    void abstractInterfaceDispatch_extractRegion() throws Exception {
        String json = """
            {"kind":"extract_region","regionKey":"douyin.comments","maxItems":25}
            """;

        ActionPayload p = mapper.readValue(json, ActionPayload.class);

        assertThat(p).isInstanceOf(ExtractRegionPayload.class);
        assertThat(((ExtractRegionPayload) p).regionKey()).isEqualTo("douyin.comments");
        assertThat(((ExtractRegionPayload) p).maxItems()).isEqualTo(25);
    }

    @Test
    void abstractInterfaceDispatch_douyinCommentNetwork() throws Exception {
        String json = """
            {"kind":"douyin_comment_network","op":"drain","maxPages":5}
            """;

        ActionPayload p = mapper.readValue(json, ActionPayload.class);

        assertThat(p).isInstanceOf(DouyinCommentNetworkPayload.class);
        assertThat(((DouyinCommentNetworkPayload) p).op()).isEqualTo("drain");
    }

    @Test
    void abstractInterfaceDispatch_kindResolvesEvenWithSharedXYFields() throws Exception {
        // {x, y} alone are ambiguous between click and move_mouse — but the
        // explicit "kind" discriminator resolves it (this is exactly the
        // Codex DEDUCTION trap the action package was redesigned to avoid).
        ActionPayload click = mapper.readValue(
                "{\"kind\":\"click\",\"x\":1,\"y\":2}", ActionPayload.class);
        ActionPayload move  = mapper.readValue(
                "{\"kind\":\"move_mouse\",\"x\":1,\"y\":2}", ActionPayload.class);

        assertThat(click).isInstanceOf(ClickPayload.class);
        assertThat(move).isInstanceOf(MoveMousePayload.class);
    }

    @Test
    void abstractInterfaceDispatch_missingKind_throws() {
        // Without a "kind" discriminator, Jackson cannot decide the subtype
        // and throws InvalidTypeIdException. This is the strict contract —
        // callers that want loose dispatch must use the concrete class.
        String json = "{\"x\":1,\"y\":2}";

        assertThatThrownBy(() -> mapper.readValue(json, ActionPayload.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void invalidEnum_deserialiseThrows() {
        String json = "{\"url\":\"https://example.com\",\"wait_for\":\"ready\"}";

        assertThatThrownBy(() -> mapper.readValue(json, NavigatePayload.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
