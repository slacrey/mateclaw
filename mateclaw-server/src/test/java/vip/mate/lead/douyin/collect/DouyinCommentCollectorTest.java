package vip.mate.lead.douyin.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.lead.douyin.browser.DouyinBrowserAdapter;
import vip.mate.lead.douyin.model.DouyinCommentItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DouyinCommentCollectorTest {

    private final DouyinCommentCollector collector = new DouyinCommentCollector();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindsAuthorAndShortCommentWhenCenterIsInsideRegion() {
        DouyinBrowserAdapter.BrowserObservation obs = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/openclaw?modal_id=1",
                "发现更多精彩视频 - 抖音搜索",
                """
                Link[ref=ref_1, frame=0]: Ly @{505,230 32x20}
                Text[ref=ref_2, frame=0]: 对于99%的人用豆包就行了。 @{505,255 190x20}
                """,
                1280,
                720,
                "",
                "");
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                520, 180, 420, 520, "test");

        List<DouyinCommentItem> comments = collector.visibleComments(obs, region);

        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().authorName()).isEqualTo("Ly");
        assertThat(comments.getFirst().text()).isEqualTo("对于99%的人用豆包就行了。");
    }

    @Test
    void detectsDeclaredCommentCount() {
        assertThat(collector.declaredCommentCount("评论 151 条")).isEqualTo(151);
        assertThat(collector.declaredCommentCount("266条评论")).isEqualTo(266);
        assertThat(collector.declaredCommentCount("全部评论 7,166")).isEqualTo(7166);
        assertThat(collector.declaredCommentCount("7,166条评论")).isEqualTo(7166);
    }

    @Test
    void detectsCommentRegionFromCommentItemsInsteadOfRightActionBar() {
        DouyinBrowserAdapter.BrowserObservation obs = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/openclaw?modal_id=1",
                "发现更多精彩视频 - 抖音搜索",
                """
                Button[ref=ref_1, frame=0]: 点赞 @{1170,180 80x44}
                StaticText[ref=ref_2, frame=0]: 151 @{1184,260 48x24}
                Button[ref=ref_3, frame=0]: 收藏 @{1170,330 80x44}
                Link[ref=ref_4, frame=0]: 川流不息 @{780,150 90x22}
                StaticText[ref=ref_5, frame=0]: 不懂就问，龙虾是什么意思？ @{830,184 220x24}
                Link[ref=ref_6, frame=0]: Ly @{780,260 32x22}
                StaticText[ref=ref_7, frame=0]: 对于99%的人用豆包就行了。 @{830,294 220x24}
                Textbox[ref=ref_8, frame=0]: 说点什么 @{780,520 260x44}
                """,
                1280,
                575,
                "",
                "");

        DouyinBrowserAdapter.RegionInfo region = collector.detectCommentRegion(obs).orElseThrow();

        assertThat(region.source()).isEqualTo("a11y-comment-items");
        assertThat(region.x()).isLessThan(860d);
        assertThat(region.x() + region.width()).isGreaterThan(1050d);
    }

    @Test
    void detectsEndMarker() {
        assertThat(collector.commentsReachedEnd("暂时没有更多评论")).isTrue();
    }

    @Test
    void detectsEndMarkerOnlyInsideCommentRegionWhenRegionProvided() {
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                900, 0, 380, 700, "test");
        String outsideOnly = "StaticText[ref=ref_1, frame=0]: 没有更多评论 @{100,640 120x20}";
        String inside = "StaticText[ref=ref_1, frame=0]: 没有更多评论 @{980,640 120x20}";

        assertThat(collector.commentsReachedEnd(outsideOnly, region)).isFalse();
        assertThat(collector.commentsReachedEnd(inside, region)).isTrue();
    }

    @Test
    void extractedRegionIgnoresGenericTextItems() throws Exception {
        var root = mapper.readTree("""
                {
                  "ok": true,
                  "results": [{
                    "payload": {
                      "items": [
                        {
                          "text": "Stop Agent",
                          "tag": "button",
                          "bbox": {"x": 10, "y": 10, "width": 80, "height": 24}
                        },
                        {
                          "itemType": "douyin_comment",
                          "author": "Ly",
                          "text": "对于99%的人用豆包就行了。",
                          "href": "https://www.douyin.com/user/MS4w",
                          "bbox": {"x": 10, "y": 40, "width": 280, "height": 48}
                        }
                      ]
                    }
                  }]
                }
                """);

        List<DouyinCommentItem> comments = collector.commentsFromExtractedRegion(root, "https://www.douyin.com/search/openclaw?modal_id=1");

        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().authorName()).isEqualTo("Ly");
        assertThat(comments.getFirst().text()).isEqualTo("对于99%的人用豆包就行了。");
    }

    @Test
    void extractedCommentKeyDoesNotDependOnWindowIndex() throws Exception {
        var first = mapper.readTree("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"douyin_comment","author":"Ly","text":"对于99%的人用豆包就行了。","bbox":{"x":10,"y":40,"width":280,"height":48}}
                ]}}]}
                """);
        var second = mapper.readTree("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"douyin_comment","author":"Other","text":"无关评论内容足够长。","bbox":{"x":10,"y":10,"width":260,"height":48}},
                  {"itemType":"douyin_comment","author":"Ly","text":"对于99%的人用豆包就行了。","bbox":{"x":10,"y":80,"width":280,"height":48}}
                ]}}]}
                """);

        DouyinCommentItem firstComment = collector.commentsFromExtractedRegion(first, "https://www.douyin.com/search/openclaw?modal_id=1").getFirst();
        DouyinCommentItem secondComment = collector.commentsFromExtractedRegion(second, "https://www.douyin.com/search/openclaw?modal_id=1").get(1);

        assertThat(firstComment.commentKey()).isEqualTo(secondComment.commentKey());
    }

    @Test
    void commentRegionIgnoresVideoActionBarAndStartsInRightPanel() {
        DouyinBrowserAdapter.BrowserObservation obs = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/openclaw?modal_id=1",
                "发现更多精彩视频 - 抖音搜索",
                """
                Button[ref=ref_1, frame=0]: 评论 151 @{640,352 96x42}
                Tab[ref=ref_2, frame=0]: 详情 @{820,88 72x28}
                Tab[ref=ref_3, frame=0]: 评论 @{900,88 72x28}
                Tab[ref=ref_4, frame=0]: TA的作品 @{980,88 96x28}
                Link[ref=ref_5, frame=0]: Ly @{850,230 32x20}
                Text[ref=ref_6, frame=0]: 对于99%的人用豆包就行了。 @{850,255 220x20}
                """,
                1280,
                575,
                "",
                "");

        DouyinBrowserAdapter.RegionInfo region = collector.detectCommentRegion(obs).orElseThrow();

        assertThat(region.x()).isGreaterThanOrEqualTo(576d);
        assertThat(region.safeX()).isGreaterThan(760d);
    }
}
