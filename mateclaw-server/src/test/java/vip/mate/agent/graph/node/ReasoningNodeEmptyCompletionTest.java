package vip.mate.agent.graph.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.NodeStreamingChatHelper.ErrorType;
import vip.mate.agent.graph.NodeStreamingChatHelper.StreamResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ReasoningNode#isEmptyCompletion} — the predicate that decides
 * whether a model turn is a blank no-op worth re-prompting (vs a real answer, a
 * tool call, or a failure handled by another branch). A blank turn must NOT be
 * accepted as a final answer; that is what made a long multi-step task quit
 * mid-way.
 */
class ReasoningNodeEmptyCompletionTest {

    private static StreamResult turn(String text, String thinking, boolean hasToolCalls) {
        return new StreamResult(text, thinking, null, List.of(), hasToolCalls, 0, 0);
    }

    @Test
    @DisplayName("No tool call + blank text + blank thinking → empty (re-prompt).")
    void blankTurnIsEmpty() {
        assertTrue(ReasoningNode.isEmptyCompletion(turn("", "", false)));
        assertTrue(ReasoningNode.isEmptyCompletion(turn("   ", "  ", false)));
        assertTrue(ReasoningNode.isEmptyCompletion(turn(null, null, false)));
    }

    @Test
    @DisplayName("Any content or thinking → not empty.")
    void contentOrThinkingNotEmpty() {
        assertFalse(ReasoningNode.isEmptyCompletion(turn("here is the answer", "", false)));
        assertFalse(ReasoningNode.isEmptyCompletion(turn("", "let me reason", false)));
    }

    @Test
    @DisplayName("A tool call is real progress → not empty.")
    void toolCallNotEmpty() {
        assertFalse(ReasoningNode.isEmptyCompletion(turn("", "", true)));
    }

    @Test
    @DisplayName("null result → not empty (nothing to re-prompt).")
    void nullNotEmpty() {
        assertFalse(ReasoningNode.isEmptyCompletion(null));
    }

    @Test
    @DisplayName("Fatal / prompt-too-long / partial belong to other branches, not 'empty'.")
    void otherFailuresNotEmpty() {
        StreamResult fatal = new StreamResult("", "", null, List.of(), false, 0, 0,
                false, "upstream boom", ErrorType.SERVER_ERROR);
        assertFalse(ReasoningNode.isEmptyCompletion(fatal));

        StreamResult promptTooLong = new StreamResult("", "", null, List.of(), false, 0, 0,
                false, null, ErrorType.PROMPT_TOO_LONG);
        assertFalse(ReasoningNode.isEmptyCompletion(promptTooLong));

        StreamResult partial = new StreamResult("", "", null, List.of(), false, 0, 0,
                true, null, ErrorType.NONE);
        assertFalse(ReasoningNode.isEmptyCompletion(partial));
    }

    @Test
    @DisplayName("完整抖音搜索排序打开视频评论区请求 → 命中确定性 harness 路由。")
    void fullDouyinVideoCommentsRequestMatchesDeterministicRoute() {
        String request = "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区";

        assertTrue(ReasoningNode.isFullDouyinSearchSortVideoCommentsRequest(request));
        assertEquals("openclaw", ReasoningNode.extractDouyinQuery(request));
    }

    @Test
    @DisplayName("完整抖音语义评论获客请求 → 命中语义评论关注私信 harness 条件。")
    void fullDouyinSemanticCommentFollowDmRequestMatchesDeterministicRoute() {
        String request = "用我的浏览器打开抖音，搜索 openclaw，点击筛选并按最多点赞排序，点开第一个视频，打开评论区，匹配评论语义“对于99%的人用豆包就行了。”，匹配上后点开这个评论用户主页，关注并点击私信，在私信输入框里输入“你好”，不要发送。";

        assertTrue(ReasoningNode.isFullDouyinSearchSortVideoCommentsRequest(request));
        assertTrue(ReasoningNode.isFullDouyinSemanticCommentFollowDmRequest(request));
        assertEquals("openclaw", ReasoningNode.extractDouyinQuery(request));
        assertEquals("对于99%的人用豆包就行了", ReasoningNode.extractDouyinCommentQuery(request));
        assertEquals("你好", ReasoningNode.extractDmDraft(request));
    }

    @Test
    @DisplayName("第一条可见评论表达 → 不再命中语义评论关注私信 harness。")
    void firstVisibleCommentFollowDmRequestDoesNotMatchSemanticRoute() {
        String request = "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区，读取评论区内容，点开第一条可见评论的用户主页，直接点击关注，然后点击私信入口，但不要发送任何消息。";

        assertTrue(ReasoningNode.isFullDouyinSearchSortVideoCommentsRequest(request));
        assertFalse(ReasoningNode.isFullDouyinSemanticCommentFollowDmRequest(request));
        assertEquals("openclaw", ReasoningNode.extractDouyinQuery(request));
    }

    @Test
    @DisplayName("前 N 个视频全部评论采集请求 → 命中多视频评论采集 harness 条件。")
    void collectCommentsAcrossVideosRequestMatchesDeterministicRoute() {
        String request = "用我的浏览器打开抖音,搜索 openclaw,点击筛选并按最多点赞排序,然后逐个打开前 3 个视频,把每个视频评论区的全部评论滚动采集下来,采集完一个自动进入下一个。";

        assertTrue(ReasoningNode.isDouyinCollectCommentsAcrossVideosRequest(request));
        assertEquals("openclaw", ReasoningNode.extractDouyinQuery(request));
        assertEquals(3, ReasoningNode.extractRequestedVideoCount(request, 1));
    }

    @Test
    @DisplayName("单视频全部评论采集请求 → 命中第一视频评论采集 harness 条件。")
    void collectFirstVideoCommentsRequestMatchesDeterministicRoute() {
        String request = "先解决抖音搜索 openclaw 后第一个视频的所有评论滚动收集全部，不要切换到第二个视频";

        assertTrue(ReasoningNode.isDouyinCollectFirstVideoCommentsRequest(request));
        assertFalse(ReasoningNode.isDouyinCollectCommentsAcrossVideosRequest(request));
        assertEquals("openclaw", ReasoningNode.extractDouyinQuery(request));
    }

    @Test
    @DisplayName("通用性保护：小红书或不完整抖音任务不会命中完整抖音 harness。")
    void nonMatchingLeadBrowserRequestsDoNotMatchDeterministicRoute() {
        assertFalse(ReasoningNode.isFullDouyinSearchSortVideoCommentsRequest(
                "用我的浏览器打开小红书，搜索 openclaw，按最多点赞排序，打开第一个笔记评论区"));
        assertFalse(ReasoningNode.isFullDouyinSearchSortVideoCommentsRequest(
                "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序"));
        assertFalse(ReasoningNode.isFullDouyinSearchSortVideoCommentsRequest(
                "在抖音搜索 openclaw，提取搜索结果数据"));
        assertFalse(ReasoningNode.isFullDouyinSemanticCommentFollowDmRequest(
                "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区"));
        assertFalse(ReasoningNode.isFullDouyinSemanticCommentFollowDmRequest(
                "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区，点开第一个评论的人，关注并点击私信"));
        assertFalse(ReasoningNode.isDouyinCollectCommentsAcrossVideosRequest(
                "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区"));
        assertFalse(ReasoningNode.isDouyinCollectFirstVideoCommentsRequest(
                "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区"));
    }
}
