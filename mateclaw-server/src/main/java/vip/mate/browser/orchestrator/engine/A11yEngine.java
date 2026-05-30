package vip.mate.browser.orchestrator.engine;

import org.springframework.stereotype.Component;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingEngine;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class A11yEngine implements GroundingEngine {
    private static final Pattern TREE_LINE_PATTERN = Pattern.compile(
            "^(\\s*)([A-Za-z][\\w-]*)\\s*\\[ref=([\\w-]+)(?:\\s*,\\s*frame=(\\d+))?\\]\\s*"
                    + "(?::\\s*(.*?))?\\s*(?:@\\{(-?\\d+),(-?\\d+)\\s+(\\d+)x(\\d+)\\})?\\s*$");

    /**
     * Text-entry roles the extension's a11y extractor uses interchangeably.
     * SPAs (Douyin, React inputs) frequently expose a search field as
     * {@code searchbox} or {@code combobox} while the LLM guesses
     * {@code textbox} (or vice-versa). We treat any of these as a match for a
     * hint asking for any other — but ONLY this closed set, so button/link/
     * checkbox/etc. stay strict and can't be cross-matched.
     */
    private static final Set<String> TEXT_ENTRY_ROLES = Set.of("textbox", "searchbox", "combobox");

    /** Unwraps the literal payload of a {@code \\Q...\\E} quoted pattern. */
    private static final Pattern QUOTED_LITERAL = Pattern.compile("^\\\\Q(.*?)\\\\E$", Pattern.DOTALL);

    @Override
    public String name() {
        return "a11y";
    }

    /**
     * A11y-tree grounding ignores {@code session} and {@code tabRef}: the
     * input snapshot is the only state it consults. The two params are part
     * of the {@link GroundingEngine} contract so {@code VisionEngine} can
     * issue auxiliary edge calls without a separate dispatch path.
     */
    @Override
    public GroundingResult ground(BrowserSession session,
                                  TabRef tabRef,
                                  PageSnapshot snapshot,
                                  GroundingHint hint) {
        return switch (hint) {
            case GroundingHint.A11yMatch m -> groundByA11yMatch(snapshot, m);
            case GroundingHint.ByRefId r -> new GroundingResult.Miss(
                    "a11y engine does not handle ByRefId hints (DOM engine owns those)");
        };
    }

    private GroundingResult groundByA11yMatch(PageSnapshot snapshot, GroundingHint.A11yMatch hint) {
        String nearLabel = hint.nearLabel();
        if (nearLabel == null || nearLabel.isBlank()) {
            return new GroundingResult.Miss("nearLabel is required for a11y disambiguation");
        }

        List<TreeLine> lines = parseTree(snapshot.tree());

        // Role gate: exact (ignore-case) OR within the text-entry equivalence
        // set so a hint role of textbox/searchbox/combobox matches a candidate
        // of any of those. Every other role stays strict.
        List<TreeLine> roleCandidates = lines.stream()
                .filter(line -> line.bbox() != null)
                .filter(line -> rolesMatch(hint.role(), line.role()))
                .toList();

        // Substring, case/Unicode-insensitive name matching. The hint pattern
        // arrives Pattern.quote-d (and usually CASE_INSENSITIVE) from
        // ExtensionBrowserTool; we recompile it adding UNICODE_CASE and switch
        // the test from .matches() (full) to .find() (substring) so a hint
        // "搜索"/"search" finds a synthesized placeholder name "搜索视频"/
        // "Search input".
        Pattern namePattern = relaxNamePattern(hint.namePattern());
        String hintLiteral = literalOf(hint.namePattern());

        // Tier 1 — direct hit: the hint pattern is found inside the candidate
        // name. Preferred whenever any candidate hits this way.
        List<TreeLine> directHits = roleCandidates.stream()
                .filter(line -> namePattern.matcher(line.name()).find())
                .toList();

        // Tier 2 — reverse (bidirectional) containment: the candidate name is
        // a substring of the hint's literal text (LLM over-specified, e.g.
        // hint "the search videos box" vs name "搜索"/"search"). Strictly lower
        // priority than a direct hit, so only consulted when tier 1 is empty.
        List<TreeLine> roleNameMatches = directHits;
        boolean reverseTier = false;
        if (roleNameMatches.isEmpty() && hintLiteral != null && !hintLiteral.isBlank()) {
            String hintLower = hintLiteral.toLowerCase(Locale.ROOT);
            roleNameMatches = roleCandidates.stream()
                    .filter(line -> !line.name().isBlank())
                    .filter(line -> hintLower.contains(line.name().toLowerCase(Locale.ROOT)))
                    .toList();
            reverseTier = !roleNameMatches.isEmpty();
        }

        if (roleNameMatches.isEmpty()) {
            return new GroundingResult.Miss(
                    "no role+name match for role=" + hint.role()
                            + " name=/" + hint.namePattern().pattern() + "/");
        }

        List<AncestorMatch> ancestorMatches = new ArrayList<>();
        for (TreeLine candidate : roleNameMatches) {
            nearestMatchingAncestor(lines, candidate, nearLabel)
                    .ifPresent(ancestor -> ancestorMatches.add(new AncestorMatch(
                            candidate,
                            ancestor.line(),
                            ancestor.label(),
                            candidate.depth() - ancestor.line().depth())));
        }

        if (ancestorMatches.isEmpty()) {
            return new GroundingResult.Miss(
                    "no candidate under headinglike ancestor containing nearLabel=\"" + nearLabel + "\"");
        }

        if (ancestorMatches.size() == 1) {
            AncestorMatch match = ancestorMatches.getFirst();
            TreeLine target = match.candidate();
            TreeLine ancestor = match.ancestor();
            return new GroundingResult.Hit(
                    new GroundedTarget(target.bbox(), target.refId()),
                    "narrowed by ancestor " + ancestor.role().toLowerCase(Locale.ROOT)
                            + ": " + match.label() + " ref=" + target.refId()
                            + (reverseTier ? " (name contained in hint)" : ""));
        }

        return new GroundingResult.Ambiguous(
                ancestorMatches.stream()
                        .map(match -> new GroundedTarget(match.candidate().bbox(), match.candidate().refId()))
                        .toList(),
                ancestorMatches.size() + " candidates narrowed by ancestor nearLabel=\"" + nearLabel + "\"");
    }

    /**
     * Role compatibility. Exact match (case-insensitive) always wins; on top of
     * that, the three text-entry roles ({@link #TEXT_ENTRY_ROLES}) are mutually
     * interchangeable. No other roles are cross-matched.
     */
    private boolean rolesMatch(String hintRole, String candidateRole) {
        if (hintRole.equalsIgnoreCase(candidateRole)) {
            return true;
        }
        String h = hintRole.toLowerCase(Locale.ROOT);
        String c = candidateRole.toLowerCase(Locale.ROOT);
        return TEXT_ENTRY_ROLES.contains(h) && TEXT_ENTRY_ROLES.contains(c);
    }

    /**
     * Returns the hint pattern with CASE_INSENSITIVE + UNICODE_CASE forced on,
     * preserving any flags the caller already set. Recompiling the (quoted)
     * pattern text is cheap and keeps the substring {@code .find()} robust for
     * mixed-case ASCII and case-folded Unicode while never altering the literal
     * the caller asked to match.
     */
    private Pattern relaxNamePattern(Pattern original) {
        int flags = original.flags() | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (flags == original.flags()) {
            return original;
        }
        return Pattern.compile(original.pattern(), flags);
    }

    /**
     * Best-effort recovery of the plain text a {@code Pattern.quote}-d pattern
     * matches, for the reverse-containment (tier 2) check. Returns {@code null}
     * when the pattern is not a simple {@code \Q...\E} literal (a real regex):
     * in that case reverse containment is skipped and only the forward
     * {@code .find()} applies, so we never treat regex metacharacters as text.
     */
    private String literalOf(Pattern pattern) {
        Matcher m = QUOTED_LITERAL.matcher(pattern.pattern());
        return m.matches() ? m.group(1) : null;
    }

    private Optional<AncestorLabel> nearestMatchingAncestor(
            List<TreeLine> lines,
            TreeLine candidate,
            String nearLabel) {
        String needle = nearLabel.toLowerCase(Locale.ROOT);
        int ancestorDepth = candidate.depth();

        for (int i = candidate.index() - 1; i >= 0; i--) {
            TreeLine line = lines.get(i);
            if (line.depth() >= ancestorDepth) {
                continue;
            }
            ancestorDepth = line.depth();
            if (isHeadinglike(line.role())) {
                Optional<String> label = matchingContextLabel(lines, line, candidate, needle);
                if (label.isPresent()) {
                    return Optional.of(new AncestorLabel(line, label.get()));
                }
            }
            if (ancestorDepth == 0) {
                break;
            }
        }

        return Optional.empty();
    }

    private Optional<String> matchingContextLabel(
            List<TreeLine> lines,
            TreeLine ancestor,
            TreeLine candidate,
            String lowerCaseNeedle) {
        if (containsIgnoreCase(ancestor.name(), lowerCaseNeedle)) {
            return Optional.of(ancestor.name());
        }

        // Containers such as articles often get their human label from a child
        // heading. Use preceding descendant headings as the container label.
        for (int i = candidate.index() - 1; i > ancestor.index(); i--) {
            TreeLine line = lines.get(i);
            if (line.depth() <= ancestor.depth()) {
                break;
            }
            if (line.role().equalsIgnoreCase("heading")
                    && containsIgnoreCase(line.name(), lowerCaseNeedle)) {
                return Optional.of(line.name());
            }
        }

        return Optional.empty();
    }

    private boolean containsIgnoreCase(String haystack, String lowerCaseNeedle) {
        return haystack.toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
    }

    private boolean isHeadinglike(String role) {
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "heading", "section", "landmark", "article", "region",
                 "main", "navigation", "banner", "complementary", "contentinfo", "form", "search" -> true;
            default -> false;
        };
    }

    private List<TreeLine> parseTree(String tree) {
        var out = new ArrayList<TreeLine>();
        int sourceIndex = 0;
        for (String raw : tree.split("\\R")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher matcher = TREE_LINE_PATTERN.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }

            BBox bbox = null;
            if (matcher.group(6) != null) {
                bbox = new BBox(
                        Integer.parseInt(matcher.group(6)),
                        Integer.parseInt(matcher.group(7)),
                        Integer.parseInt(matcher.group(8)),
                        Integer.parseInt(matcher.group(9)));
            }

            out.add(new TreeLine(
                    sourceIndex++,
                    indentationDepth(matcher.group(1)),
                    matcher.group(2).trim(),
                    matcher.group(3),
                    matcher.group(5) == null ? "" : matcher.group(5).trim(),
                    bbox));
        }
        return out;
    }

    private int indentationDepth(String indentation) {
        int columns = 0;
        for (int i = 0; i < indentation.length(); i++) {
            columns += indentation.charAt(i) == '\t' ? 2 : 1;
        }
        return columns / 2;
    }

    private record TreeLine(int index, int depth, String role, String refId, String name, BBox bbox) {}

    private record AncestorLabel(TreeLine line, String label) {}

    private record AncestorMatch(TreeLine candidate, TreeLine ancestor, String label, int distance) {}
}
