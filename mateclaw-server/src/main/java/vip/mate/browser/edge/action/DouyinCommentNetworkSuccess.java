package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DouyinCommentNetworkSuccess(
        String op,
        List<Page> pages,
        Integer capturedCount,
        Integer droppedCount
) implements ActionSuccessPayload {

    public DouyinCommentNetworkSuccess {
        if (op == null || op.isBlank()) {
            op = "";
        }
        pages = pages == null ? List.of() : List.copyOf(pages);
        if (capturedCount == null) {
            capturedCount = pages.size();
        }
        if (droppedCount == null) {
            droppedCount = 0;
        }
    }

    public record Page(
            String url,
            String requestId,
            Integer status,
            String body,
            Boolean base64Encoded,
            Long capturedAtMs
    ) {
        public Page {
            if (url == null) {
                url = "";
            }
            if (requestId == null) {
                requestId = "";
            }
            if (body == null) {
                body = "";
            }
            if (base64Encoded == null) {
                base64Encoded = false;
            }
        }
    }
}
