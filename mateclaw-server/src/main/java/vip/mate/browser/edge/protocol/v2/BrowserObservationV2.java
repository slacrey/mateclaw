package vip.mate.browser.edge.protocol.v2;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record BrowserObservationV2(
        String url,
        String title,
        Map<String, Object> viewport,
        List<Map<String, Object>> tabs,
        List<Map<String, Object>> regions,
        List<Map<String, Object>> axNodes,
        List<Map<String, Object>> domHints,
        @JsonProperty("screenshotRef") String screenshotRef,
        List<Map<String, Object>> pageEvents,
        String fingerprint
) {
}
