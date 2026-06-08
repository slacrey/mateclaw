package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record ClickProfileActionPayload(
        @JsonProperty("labels") List<String> labels
) implements ActionPayload {

    public ClickProfileActionPayload {
        labels = labels == null
                ? List.of()
                : labels.stream()
                .filter(label -> label != null && !label.isBlank())
                .map(String::trim)
                .toList();
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("labels is required");
        }
    }
}
