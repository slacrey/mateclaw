package vip.mate.lead.douyin.match;

import org.junit.jupiter.api.Test;
import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.DouyinCommentItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMatcherTest {

    private final CommentMatcher matcher = new CommentMatcher();

    @Test
    void exactCommentHitsOneHundredPercent() {
        DouyinCommentItem ly = comment("Ly", "对于99%的人用豆包就行了。");

        CommentMatchResult result = matcher.score(ly, "对于99%的人用豆包就行了。");

        assertThat(result.matched()).isTrue();
        assertThat(result.score()).isEqualTo(1.0d);
        assertThat(result.reason()).startsWith("exact");
    }

    @Test
    void punctuationDifferenceStillExact() {
        DouyinCommentItem ly = comment("Ly", "对于99%的人用豆包就行了");

        CommentMatchResult result = matcher.score(ly, "对于99%的人用豆包就行了。");

        assertThat(result.matched()).isTrue();
        assertThat(result.score()).isEqualTo(1.0d);
    }

    @Test
    void nearMatchDoesNotBlockLaterExactMatch() {
        DouyinCommentItem near = comment("宝宝甜妹", "九成以上的普通人目前根本没必要用这个东西，目前应用场景也就是一些电脑端的工作可以使用");
        DouyinCommentItem exact = comment("Ly", "对于99%的人用豆包就行了。");

        List<CommentMatchResult> results = matcher.match(List.of(near, exact), "对于99%的人用豆包就行了。");

        assertThat(results.getFirst().comment().authorName()).isEqualTo("Ly");
        assertThat(results.getFirst().score()).isEqualTo(1.0d);
    }

    @Test
    void authorNameIsNotAMatchFilter() {
        DouyinCommentItem ly = comment("Ly", "对于99%的人用豆包就行了。");

        List<CommentMatchResult> results = matcher.matched(List.of(ly), "对于99%的人用豆包就行了。");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().comment().authorName()).isEqualTo("Ly");
    }

    @Test
    void multipleRulePhrasesMatchAgainstCommentTextOnly() {
        DouyinCommentItem mumaText = comment("不是木马作者", "他叫木马");
        DouyinCommentItem ly = comment("Ly", "对于99%的人用豆包就行了。");
        DouyinCommentItem authorOnly = comment("木马", "这个工具还不错");

        List<CommentMatchResult> results = matcher.matched(
                List.of(authorOnly, mumaText, ly),
                "匹配的词语是 他叫木马 及 对于99%的人用豆包就行了。");

        assertThat(results)
                .extracting(result -> result.comment().text())
                .containsExactly("他叫木马", "对于99%的人用豆包就行了。");
        assertThat(results)
                .extracting(result -> result.comment().authorName())
                .doesNotContain("木马");
    }

    private DouyinCommentItem comment(String author, String text) {
        return new DouyinCommentItem(
                "video-1",
                "comment-" + author,
                null,
                author,
                "https://www.douyin.com/user/" + author,
                null,
                text,
                null,
                null,
                null,
                null);
    }
}
