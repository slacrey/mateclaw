package vip.mate.browser.orchestrator.engine;

import org.springframework.stereotype.Component;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class A11yEngine implements GroundingEngine {
    private static final Pattern TREE_LINE_PATTERN = Pattern.compile(
            "^(\\s*)([A-Za-z][\\w-]*)\\s*\\[ref=([\\w-]+)(?:\\s*,\\s*frame=(\\d+))?\\]\\s*"
                    + "(?::\\s*(.*?))?\\s*(?:@\\{(-?\\d+),(-?\\d+)\\s+(\\d+)x(\\d+)\\})?\\s*$");

    @Override
    public String name() {
        return "a11y";
    }

    @Override
    public GroundingResult ground(PageSnapshot snapshot, GroundingHint hint) {
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
        List<TreeLine> roleNameMatches = lines.stream()
                .filter(line -> line.bbox() != null)
                .filter(line -> line.role().equalsIgnoreCase(hint.role()))
                .filter(line -> hint.namePattern().matcher(line.name()).matches())
                .toList();

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
                            + ": " + match.label() + " ref=" + target.refId());
        }

        return new GroundingResult.Ambiguous(
                ancestorMatches.stream()
                        .map(match -> new GroundedTarget(match.candidate().bbox(), match.candidate().refId()))
                        .toList(),
                ancestorMatches.size() + " candidates narrowed by ancestor nearLabel=\"" + nearLabel + "\"");
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
