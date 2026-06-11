package vip.mate.lead.douyin.match;

import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.CommentMatchRule;

import java.util.List;

@FunctionalInterface
public interface CommentAiClassifier {

    List<CommentMatchResult> classify(List<CommentMatchRule> rules, List<CommentMatchResult> candidates);
}
