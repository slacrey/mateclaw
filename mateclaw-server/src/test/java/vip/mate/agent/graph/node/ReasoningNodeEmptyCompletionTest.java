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
    @DisplayName("完整抖音评论获客调试请求 → 命中第一条评论关注私信 harness 条件。")
    void fullDouyinFirstCommentFollowDmRequestMatchesDeterministicRoute() {
        String request = "从头开始测试：用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区，获取评论区内容，点开第一个评论的人，关注并点击私信，不发送";

        assertTrue(ReasoningNode.isFullDouyinSearchSortVideoCommentsRequest(request));
        assertTrue(ReasoningNode.isFullDouyinFirstCommentFollowDmRequest(request));
        assertEquals("openclaw", ReasoningNode.extractDouyinQuery(request));
    }

    @Test
    @DisplayName("第一条可见评论表达 → 也命中完整关注私信 harness。")
    void firstVisibleCommentFollowDmRequestMatchesDeterministicRoute() {
        String request = "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区，读取评论区内容，点开第一条可见评论的用户主页，直接点击关注，然后点击私信入口，但不要发送任何消息。";

        assertTrue(ReasoningNode.isFullDouyinSearchSortVideoCommentsRequest(request));
        assertTrue(ReasoningNode.isFullDouyinFirstCommentFollowDmRequest(request));
        assertEquals("openclaw", ReasoningNode.extractDouyinQuery(request));
    }

    @Test
    @DisplayName("当前评论区继续执行 → 命中第一条评论关注私信 current-page harness。")
    void currentCommentsFirstVisibleCommentFollowDmRequestMatchesDeterministicRoute() {
        assertTrue(ReasoningNode.isCurrentDouyinFirstCommentFollowDmRequest(
                "评论区已经打开，点开第一条可见评论的用户主页，直接点击关注，然后点击私信入口，但不要发送任何消息。"));
        assertTrue(ReasoningNode.isCurrentDouyinFirstCommentFollowDmRequest(
                "继续，点开第一个评论的人主页，关注并点击私信，不发送"));
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
        assertFalse(ReasoningNode.isFullDouyinFirstCommentFollowDmRequest(
                "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区"));
        assertFalse(ReasoningNode.isFullDouyinFirstCommentFollowDmRequest(
                "用我的浏览器打开抖音，搜索 openclaw，点击筛选，按最多点赞排序，点开第一个视频，打开评论区，点开第一个评论的人，关注并点击私信"));
        assertFalse(ReasoningNode.isCurrentDouyinFirstCommentFollowDmRequest(
                "评论区已经打开，点开第一条评论的用户主页，只读取资料，不关注不私信"));
    }
}
