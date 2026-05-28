package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Set;

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public record MoveMousePayload(
        double x,
        double y,
        String profile
) implements ActionPayload {

    private static final Set<String> PROFILE_VALUES = Set.of("natural", "linear");

    public MoveMousePayload {
        if (profile == null) {
            profile = "natural";
        }
        if (!PROFILE_VALUES.contains(profile)) {
            throw new IllegalArgumentException("profile must be one of " + PROFILE_VALUES);
        }
    }
}
