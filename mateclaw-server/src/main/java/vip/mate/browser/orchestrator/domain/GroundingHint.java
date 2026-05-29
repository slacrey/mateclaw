package vip.mate.browser.orchestrator.domain;

import java.util.regex.Pattern;

/**
 * Input to a {@link GroundingEngine}. Sealed so each engine can pattern-match
 * over the hint shape it understands.
 *
 * <p>Phase 2 ships two hint kinds; Phase 3 SOP work will add more (semantic
 * description, CSS selector list with confidence learning, vision prompt).
 */
public sealed interface GroundingHint
        permits GroundingHint.A11yMatch, GroundingHint.ByRefId {

    /** Filter used when requesting an A11y snapshot ('interactive' / 'all' / 'default'). */
    String filter();

    /** Match by role + accessible-name regex against the A11y tree. */
    record A11yMatch(String role, Pattern namePattern, String filter, String nearLabel) implements GroundingHint {
        public A11yMatch {
            if (role == null || role.isBlank())
                throw new IllegalArgumentException("role is required");
            if (namePattern == null)
                throw new IllegalArgumentException("namePattern is required");
            if (filter == null) filter = "interactive";
            // nearLabel may be null; engines that do not honor it ignore it.
        }

        /** Convenience constructor — default filter='interactive'. */
        public A11yMatch(String role, Pattern namePattern) {
            this(role, namePattern, "interactive", null);
        }

        public A11yMatch(String role, Pattern namePattern, String filter) {
            this(role, namePattern, filter, null);
        }
    }

    /** Directly request the element behind ref_N. The snapshot must already
     *  carry the ref tag (caller is responsible for snapshot freshness). */
    record ByRefId(String refId, String filter) implements GroundingHint {
        public ByRefId {
            if (refId == null || refId.isBlank())
                throw new IllegalArgumentException("refId is required");
            if (filter == null) filter = "interactive";
        }

        public ByRefId(String refId) {
            this(refId, "interactive");
        }
    }
}
