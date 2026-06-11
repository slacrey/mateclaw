package vip.mate.lead.douyin.model;

import java.util.ArrayList;
import java.util.List;

public record CommentMatchRule(
        String mode,
        String value
) {
    public static final String MODE_KEYWORD = "keyword";
    public static final String MODE_SEMANTIC = "semantic";

    public CommentMatchRule {
        mode = normalizeMode(mode);
        value = value == null ? "" : value.trim();
    }

    public boolean keyword() {
        return MODE_KEYWORD.equals(mode);
    }

    public boolean semantic() {
        return MODE_SEMANTIC.equals(mode);
    }

    public boolean usable() {
        return !value.isBlank();
    }

    public static CommentMatchRule keyword(String value) {
        return new CommentMatchRule(MODE_KEYWORD, value);
    }

    public static CommentMatchRule semantic(String value) {
        return new CommentMatchRule(MODE_SEMANTIC, value);
    }

    public static List<CommentMatchRule> normalize(List<CommentMatchRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<CommentMatchRule> out = new ArrayList<>();
        for (CommentMatchRule rule : rules) {
            if (rule == null) {
                continue;
            }
            CommentMatchRule normalized = new CommentMatchRule(rule.mode(), rule.value());
            if (normalized.usable()) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    private static String normalizeMode(String mode) {
        if (mode == null) {
            return MODE_KEYWORD;
        }
        String normalized = mode.trim().toLowerCase();
        return MODE_SEMANTIC.equals(normalized) ? MODE_SEMANTIC : MODE_KEYWORD;
    }
}
