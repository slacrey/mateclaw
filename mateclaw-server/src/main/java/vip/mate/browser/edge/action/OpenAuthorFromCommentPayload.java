package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record OpenAuthorFromCommentPayload(
        @JsonProperty("commentText") String commentText,
        @JsonProperty("authorName") String authorName,
        @JsonProperty("authorProfileUrl") String authorProfileUrl
) implements ActionPayload {

    public OpenAuthorFromCommentPayload {
        if (commentText == null || commentText.isBlank()) {
            throw new IllegalArgumentException("commentText is required");
        }
        authorName = authorName == null ? "" : authorName.trim();
        authorProfileUrl = authorProfileUrl == null ? "" : authorProfileUrl.trim();
    }

    public OpenAuthorFromCommentPayload(String commentText, String authorName) {
        this(commentText, authorName, "");
    }
}
