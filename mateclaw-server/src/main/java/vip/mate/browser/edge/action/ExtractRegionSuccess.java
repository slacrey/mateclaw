package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ExtractRegionSuccess(
        @JsonProperty("regionKey") String regionKey,
        List<Item> items,
        Map<String, Object> diagnostics
) implements ActionSuccessPayload {

    public ExtractRegionSuccess(String regionKey, List<Item> items) {
        this(regionKey, items, Map.of());
    }

    public ExtractRegionSuccess {
        if (regionKey == null || regionKey.isBlank()) {
            throw new IllegalArgumentException("regionKey is required");
        }
        items = items == null ? List.of() : List.copyOf(items);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(diagnostics));
    }

    public record Item(
            String text,
            String role,
            String tag,
            String href,
            BBox bbox,
            @JsonProperty("itemType") String itemType,
            String author,
            List<String> hrefs,
            @JsonProperty("visibleInRegion") Boolean visibleInRegion
    ) {
        public Item(String text,
                    String role,
                    String tag,
                    String href,
                    BBox bbox,
                    String itemType,
                    String author,
                    List<String> hrefs) {
            this(text, role, tag, href, bbox, itemType, author, hrefs, null);
        }

        public Item {
            if (text == null) {
                text = "";
            }
            hrefs = hrefs == null ? List.of() : List.copyOf(hrefs);
        }
    }

    public record BBox(double x, double y, double width, double height) {
    }
}
