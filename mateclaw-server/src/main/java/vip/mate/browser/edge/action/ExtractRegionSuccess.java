package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ExtractRegionSuccess(
        @JsonProperty("regionKey") String regionKey,
        List<Item> items
) implements ActionSuccessPayload {

    public ExtractRegionSuccess {
        if (regionKey == null || regionKey.isBlank()) {
            throw new IllegalArgumentException("regionKey is required");
        }
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(
            String text,
            String role,
            String tag,
            String href,
            BBox bbox,
            @JsonProperty("itemType") String itemType,
            String author,
            List<String> hrefs
    ) {
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
