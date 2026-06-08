package vip.mate.lead.douyin.match;

import org.springframework.stereotype.Component;
import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.DouyinCommentItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class CommentMatcher {

    public List<CommentMatchResult> match(List<DouyinCommentItem> comments, String rule) {
        if (comments == null || comments.isEmpty()) {
            return List.of();
        }
        List<CommentMatchResult> results = new ArrayList<>();
        for (DouyinCommentItem comment : comments) {
            results.add(score(comment, rule));
        }
        results.sort(Comparator
                .comparing(CommentMatchResult::matched, Comparator.reverseOrder())
                .thenComparing(CommentMatchResult::score, Comparator.reverseOrder()));
        return results;
    }

    public List<CommentMatchResult> matched(List<DouyinCommentItem> comments, String rule) {
        return match(comments, rule).stream()
                .filter(CommentMatchResult::matched)
                .toList();
    }

    public CommentMatchResult score(DouyinCommentItem comment, String rule) {
        String candidate = clean(comment == null ? "" : comment.text());
        List<String> targets = cleanTargetRules(rule);
        if (candidate.isBlank() || targets.isEmpty()) {
            return new CommentMatchResult(comment, false, 0d, "empty_text");
        }
        CommentMatchResult best = null;
        for (String target : targets) {
            CommentMatchResult result = scoreAgainstTarget(comment, candidate, target);
            if (best == null || result.score() > best.score() || (result.matched() && !best.matched())) {
                best = result;
            }
        }
        return best == null ? new CommentMatchResult(comment, false, 0d, "empty_text") : best;
    }

    private CommentMatchResult scoreAgainstTarget(DouyinCommentItem comment, String candidate, String target) {
        if (normalizeExact(candidate).equals(normalizeExact(target))) {
            return new CommentMatchResult(comment, true, 1.0d, "exact_text_match");
        }
        if (normalizeExact(candidate).contains(normalizeExact(target))
                || normalizeExact(target).contains(normalizeExact(candidate))) {
            return new CommentMatchResult(comment, true, 1.0d, "exact_text_contains");
        }

        double score = semanticScore(target, candidate);
        boolean matched = score >= 0.70d;
        return new CommentMatchResult(
                comment,
                matched,
                score,
                matched ? "semantic_comment_match" : "semantic_candidate_only");
    }

    private double semanticScore(String target, String candidate) {
        Set<String> targetConcepts = concepts(target);
        Set<String> candidateConcepts = concepts(candidate);
        if (targetConcepts.isEmpty()) {
            return 0d;
        }
        int hits = 0;
        for (String concept : targetConcepts) {
            if (candidateConcepts.contains(concept)) {
                hits++;
            }
        }
        double conceptScore = hits / (double) targetConcepts.size();
        Set<String> targetTerms = terms(target);
        Set<String> candidateTerms = terms(candidate);
        int termHits = 0;
        for (String term : targetTerms) {
            if (candidateTerms.contains(term) || candidate.toLowerCase(Locale.ROOT).contains(term)) {
                termHits++;
            }
        }
        double termScore = targetTerms.isEmpty() ? 0d : termHits / (double) targetTerms.size();
        double score = Math.max(conceptScore, 0.35d * conceptScore + 0.65d * termScore);
        if (target.contains("豆包") && !candidate.contains("豆包")) {
            score = Math.min(score, 0.68d);
        }
        if ((target.contains("99") || target.contains("大多数")) && containsMajority(candidate)) {
            score = Math.max(score, 0.48d);
        }
        return Math.max(0d, Math.min(0.99d, score));
    }

    private Set<String> concepts(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        if (containsMajority(text)) out.add("majority");
        if (normalized.contains("普通人") || normalized.contains("一般人") || normalized.contains("99%的人")) out.add("ordinary_people");
        if (normalized.contains("豆包")) out.add("doubao");
        if (normalized.contains("就行") || normalized.contains("够了") || normalized.contains("可以")
                || normalized.contains("没必要") || normalized.contains("根本没必要")) out.add("enough_or_unnecessary");
        return out;
    }

    private boolean containsMajority(String text) {
        return text.contains("99%")
                || text.contains("百分之九十九")
                || text.contains("九成")
                || text.contains("大多数")
                || text.contains("绝大多数")
                || text.contains("大部分");
    }

    private Set<String> terms(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : normalizeExact(text).split("[,，。.!！?？;；:\\s]+")) {
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        return out;
    }

    private String normalizeExact(String text) {
        return clean(text)
                .toLowerCase(Locale.ROOT)
                .replace("％", "%")
                .replaceAll("[\\s\\p{Punct}，。！？；：“”‘’、]", "");
    }

    private String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private List<String> cleanTargetRules(String rule) {
        String cleaned = clean(rule);
        if (cleaned.isBlank()) {
            return List.of();
        }
        java.util.regex.Matcher quoted = java.util.regex.Pattern
                .compile("[「『“\"]([^」』”\"]{2,200})[」』”\"]")
                .matcher(cleaned);
        List<String> quotedTargets = new ArrayList<>();
        while (quoted.find()) {
            quotedTargets.add(clean(quoted.group(1)));
        }
        if (!quotedTargets.isEmpty()) {
            return quotedTargets;
        }
        java.util.regex.Matcher explicit = java.util.regex.Pattern
                .compile("(?:评论(?:正文|内容)?|匹配(?:的)?(?:词语|文本|评论)?|目标评论)\\s*(?:是|为|:|：)\\s*(.{2,200})$")
                .matcher(cleaned);
        if (explicit.find()) {
            return splitRuleSegments(explicit.group(1));
        }
        return splitRuleSegments(cleaned);
    }

    private List<String> splitRuleSegments(String value) {
        String cleaned = clean(value);
        String[] parts = cleaned.split("\\s*(?:及|和|并且|、|;|；)\\s*");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String candidate = clean(part);
            if (!candidate.isBlank()) {
                out.add(candidate);
            }
        }
        return out.isEmpty() ? List.of(cleaned) : out;
    }
}
