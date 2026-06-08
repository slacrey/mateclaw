package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record DouyinCommentNetworkPayload(
        String op,
        Integer maxPages,
        Integer maxBodyBytes,
        Integer ttlMs
) implements ActionPayload {

    private static final Set<String> OP_VALUES = Set.of("start", "drain", "stop");

    public DouyinCommentNetworkPayload {
        if (op == null) {
            throw new IllegalArgumentException("op is required");
        }
        if (!OP_VALUES.contains(op)) {
            throw new IllegalArgumentException("op must be one of " + OP_VALUES);
        }
        if (maxPages != null && maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be >= 1");
        }
        if (maxBodyBytes != null && maxBodyBytes < 1) {
            throw new IllegalArgumentException("maxBodyBytes must be >= 1");
        }
        if (ttlMs != null && ttlMs < 1) {
            throw new IllegalArgumentException("ttlMs must be >= 1");
        }
    }
}
