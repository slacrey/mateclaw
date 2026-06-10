package vip.mate.lead.douyin;

import org.springframework.stereotype.Service;
import vip.mate.lead.douyin.browser.DouyinBrowserAdapter;
import vip.mate.lead.douyin.browser.DouyinBrowserException;
import vip.mate.lead.douyin.match.CommentMatcher;
import vip.mate.lead.douyin.model.CommentCollectionResult;
import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.model.DouyinLeadRunSummary;
import vip.mate.lead.douyin.model.EngagementResult;
import vip.mate.lead.douyin.store.LeadPersistenceService;
import vip.mate.os.run.model.AgentRunStatus;
import vip.mate.os.run.model.AgentStepEntity;
import vip.mate.os.run.model.AgentStepStatus;
import vip.mate.os.run.model.LeadProfileEntity;
import vip.mate.os.run.runtime.AgentRunKernel;
import vip.mate.os.run.runtime.AgentStepRequest;
import vip.mate.os.run.runtime.RunCancellationService;
import vip.mate.os.run.runtime.RunEvent;
import vip.mate.os.run.runtime.RunEventPublisher;
import vip.mate.os.run.runtime.StepCloseRequest;
import vip.mate.os.run.runtime.StepLedgerService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Service
public class DouyinLeadAcquisitionExecutor {

    private final DouyinBrowserAdapter browser;
    private final CommentMatcher matcher;
    private final LeadPersistenceService persistence;
    private final AgentRunKernel runKernel;
    private final StepLedgerService steps;
    private final RunEventPublisher events;
    private final RunCancellationService cancellation;

    public DouyinLeadAcquisitionExecutor(DouyinBrowserAdapter browser,
                                         CommentMatcher matcher,
                                         LeadPersistenceService persistence,
                                         AgentRunKernel runKernel,
                                         StepLedgerService steps,
                                         RunEventPublisher events,
                                         RunCancellationService cancellation) {
        this.browser = browser;
        this.matcher = matcher;
        this.persistence = persistence;
        this.runKernel = runKernel;
        this.steps = steps;
        this.events = events;
        this.cancellation = cancellation;
    }

    public void execute(Long runId, Long taskId, DouyinLeadAcquisitionInput input) {
        List<VideoRunState> videos = new ArrayList<>();
        DouyinLeadRunSummary summary = DouyinLeadRunSummary.empty(input.videoLimit());
        try {
            runKernel.startRun(runId);
            events.publish(new RunEvent(runId, null, "lead.search.started", "info", payload(
                    "keyword", input.keyword(),
                    "requestedVideoLimit", input.videoLimit()), null));
            DouyinBrowserAdapter.BrowserObservation searchObservation = step(runId, "open_douyin_search", "browser.act",
                    () -> browser.openDouyinAndSearch(input));
            events.publish(new RunEvent(runId, null, "lead.search.completed", "info", payload(
                    "keyword", input.keyword(),
                    "url", searchObservation.url(),
                    "title", searchObservation.title()), null));

            events.publish(new RunEvent(runId, null, "lead.sort.started", "info", payload(
                    "sort", input.sort(),
                    "keyword", input.keyword()), null));
            DouyinBrowserAdapter.BrowserObservation sortObservation = step(runId, "apply_sort", "browser.act",
                    () -> browser.applySort(input));
            events.publish(new RunEvent(runId, null, "lead.sort.completed", "info", payload(
                    "sort", input.sort(),
                    "url", sortObservation.url(),
                    "title", sortObservation.title()), null));

            for (int videoIndex = 0; videoIndex < input.videoLimit(); videoIndex++) {
                assertNotCancelled(runId);
                VideoRunState video = new VideoRunState(videoIndex);
                videos.add(video);
                events.publish(new RunEvent(runId, null, "lead.video.started", "info", Map.of(
                        "videoIndex", videoIndex,
                        "videoNumber", videoIndex + 1,
                        "requestedVideoLimit", input.videoLimit()), null));
                try {
                    executeVideo(runId, taskId, input, video);
                    video.status = "succeeded";
                    events.publish(new RunEvent(runId, null, "lead.video.completed", "info",
                            video.toPayload(), null));
                } catch (Exception videoError) {
                    if (videoError instanceof DouyinBrowserException dbe
                            && "RUN_CANCELLED".equals(dbe.code())) {
                        throw videoError;
                    }
                    video.status = "failed";
                    video.failureCode = videoError instanceof DouyinBrowserException dbe
                            ? dbe.code()
                            : "VIDEO_FAILED";
                    video.failureMessage = videoError.getMessage() == null
                            ? video.failureCode
                            : videoError.getMessage();
                    events.publish(new RunEvent(runId, null, "lead.video.failed", "error",
                            video.toPayload(), null));
                }
            }
            summary = summarize(input.videoLimit(), videos);
            boolean anySucceeded = summary.succeededVideos() > 0;
            persistence.completeTask(taskId, anySucceeded ? "succeeded" : "failed", summary);
            events.publish(new RunEvent(runId, null, "lead.run.summary",
                    anySucceeded ? "info" : "error", summaryPayload(summary), null));
            if (anySucceeded) {
                runKernel.finishSucceeded(runId, "lead-task:" + taskId);
            } else {
                VideoRunState firstFailure = firstFailure(videos);
                runKernel.finishFailed(runId,
                        firstFailure == null || firstFailure.failureCode.isBlank()
                                ? "ALL_VIDEOS_FAILED"
                                : firstFailure.failureCode,
                        firstFailure == null || firstFailure.failureMessage.isBlank()
                                ? "No requested Douyin videos completed successfully"
                                : firstFailure.failureMessage);
            }
        } catch (Exception e) {
            String code = e instanceof DouyinBrowserException dbe ? dbe.code() : "DOUYIN_RUN_FAILED";
            String message = e.getMessage() == null ? code : e.getMessage();
            summary = videos.isEmpty() ? summary : summarize(input.videoLimit(), videos);
            persistence.completeTask(taskId, "failed", summary);
            safeFinishFailed(runId, code, message);
            events.publish(new RunEvent(runId, null, "lead.run.failed", "error",
                    Map.of("code", code, "message", message), null));
        } finally {
        }
    }

    private void executeVideo(Long runId, Long taskId, DouyinLeadAcquisitionInput input,
                              VideoRunState video) throws Exception {
        DouyinBrowserAdapter.BrowserObservation openedVideo = step(runId,
                video.stepKey("open_first_video"),
                "browser.act",
                () -> browser.openVideo(video.index));
        video.videoUrl = openedVideo.url();
        video.videoTitle = openedVideo.title();
        video.videoTarget = openedVideo.message() == null ? "" : openedVideo.message();
        events.publish(new RunEvent(runId, null, "lead.video.opened", "info", payload(
                "videoIndex", video.index,
                "url", openedVideo.url(),
                "title", openedVideo.title(),
                "target", video.videoTarget), null));

        DouyinBrowserAdapter.BrowserObservation openedComments = step(runId,
                video.stepKey("open_comments"),
                "browser.act",
                browser::openComments);
        events.publish(new RunEvent(runId, null, "lead.comments.opened", "info", payload(
                "videoIndex", video.index,
                "url", openedComments.url(),
                "code", openedComments.code() == null ? "" : openedComments.code(),
                "method", openedComments.message() == null ? "" : openedComments.message()), null));

        DouyinBrowserAdapter.RegionInfo region = step(runId,
                video.stepKey("detect_comment_region"),
                "browser.observe",
                browser::detectCommentRegion);
        events.publish(new RunEvent(runId, null, "lead.comments.region_detected", "info", payload(
                "videoIndex", video.index,
                "regionKey", region.regionKey(),
                "x", region.x(),
                "y", region.y(),
                "width", region.width(),
                "height", region.height(),
                "safeX", region.safeX(),
                "safeY", region.safeY(),
                "source", region.source()), null));

        CommentCollectionResult collection = step(runId,
                video.stepKey("collect_all_comments"),
                "browser.extract",
                () -> {
                    events.publish(new RunEvent(runId, null, "lead.comments.collecting", "info", payload(
                            "videoIndex", video.index,
                            "regionKey", region.regionKey(),
                            "source", region.source()), null));
                    return browser.collectAllComments(region);
                });
        video.collection = collection;
        persistence.saveComments(taskId, runId, collection.comments());
        events.publish(new RunEvent(runId, null, "lead.comments.collected", "info",
                collectionPayload(video.index, collection), null));
        if (!collection.complete()) {
            throw new DouyinBrowserException("COMMENT_COLLECTION_INCOMPLETE",
                    "评论未完整采集: videoIndex=" + video.index
                            + ", declared=" + collection.declaredCommentCount()
                            + ", collected=" + collection.comments().size()
                            + ", stopReason=" + collection.stopReason());
        }

        String matchRule = input.commentMatchRule() == null ? "" : input.commentMatchRule().trim();
        if (matchRule.isBlank()) {
            events.publish(new RunEvent(runId, null, "lead.comment.match_skipped", "info", Map.of(
                    "videoIndex", video.index,
                    "reason", "no_comment_match_rule"), null));
            events.publish(new RunEvent(runId, null, "lead.engagement.skipped", "info", Map.of(
                    "videoIndex", video.index,
                    "reason", "no_comment_match_rule",
                    "matchedComments", 0), null));
            return;
        }

        List<CommentMatchResult> matches = step(runId,
                video.stepKey("match_comment_text"),
                "llm.classify.batch",
                () -> matcher.matched(collection.comments(), matchRule));
        video.matches = matches;
        persistence.markMatches(taskId, matches);
        events.publish(new RunEvent(runId, null, "lead.comment.matched", "info", Map.of(
                "videoIndex", video.index,
                "matchedComments", matches.size(),
                "rule", matchRule), null));
        if (!input.engage()) {
            events.publish(new RunEvent(runId, null, "lead.engagement.skipped", "info", Map.of(
                    "videoIndex", video.index,
                    "reason", "engagement_disabled",
                    "matchedComments", matches.size()), null));
            return;
        }
        if (matches.isEmpty()) {
            events.publish(new RunEvent(runId, null, "lead.engagement.skipped", "info", Map.of(
                    "videoIndex", video.index,
                    "reason", "no_matched_comments",
                    "matchedComments", 0), null));
            return;
        }
        executeEngagementsForVideo(runId, taskId, input, video);
    }

    private void executeEngagementsForVideo(Long runId, Long taskId, DouyinLeadAcquisitionInput input,
                                            VideoRunState video) throws Exception {
        for (CommentMatchResult match : video.matches) {
            assertNotCancelled(runId);
            events.publish(new RunEvent(runId, null, "lead.engagement.started", "info", payload(
                    "videoIndex", video.index,
                    "author", match.comment().authorName(),
                    "commentKey", match.comment().commentKey(),
                    "sendDm", input.sendDm()), null));
            EngagementResult engagement = step(runId,
                    video.stepKey("engage_matched_comment_author_" + safeKey(match.comment().commentKey())),
                    "browser.act",
                    () -> browser.followAndDraft(match.comment(), input.dmDraft(), input.sendDm()));
            video.engagements.add(engagement);
            LeadProfileEntity profile = persistence.saveProfile(taskId, runId, engagement);
            Long commentId = persistence.findCommentId(taskId, match.comment().commentKey());
            persistence.saveEngagement(taskId, runId, profile == null ? null : profile.getId(), commentId,
                    "dm_draft", engagement, input.dmDraft());
            events.publish(new RunEvent(runId, null, "lead.engagement.completed", "info", payload(
                    "videoIndex", video.index,
                    "author", engagement.author(),
                    "commentKey", match.comment().commentKey(),
                    "followConfirmed", engagement.followConfirmed(),
                    "dmOpened", engagement.dmOpened(),
                    "draftTyped", engagement.draftTyped(),
                    "sent", engagement.sent(),
                    "status", engagement.status()), null));
            boolean sendRequiredButNotConfirmed = input.sendDm() && !engagement.sent();
            if (!engagement.draftTyped() || sendRequiredButNotConfirmed) {
                throw new DouyinBrowserException(
                        engagement.failureCode() == null ? "ENGAGEMENT_FAILED" : engagement.failureCode(),
                        engagement.failureMessage() == null ? "互动执行未完成" : engagement.failureMessage());
            }
        }
    }

    private <T> T step(Long runId, String key, String type, Callable<T> work) throws Exception {
        assertNotCancelled(runId);
        AgentStepEntity step = steps.openStep(new AgentStepRequest(
                runId,
                null,
                key,
                null,
                type,
                key,
                policyTagsFor(type),
                null));
        try {
            T result = work.call();
            steps.closeStep(new StepCloseRequest(
                    step.getId(),
                    AgentStepStatus.SUCCEEDED,
                    "ok",
                    key + ":checkpoint",
                    null,
                    null,
                    null,
                    null));
            return result;
        } catch (Exception e) {
            steps.closeStep(new StepCloseRequest(
                    step.getId(),
                    AgentStepStatus.FAILED,
                    null,
                    null,
                    e instanceof DouyinBrowserException dbe ? dbe.code() : "STEP_FAILED",
                    e.getMessage(),
                    null,
                    null));
            throw e;
        }
    }

    private void assertNotCancelled(Long runId) {
        if (cancellation.isCancellationRequested(runId)) {
            runKernel.transition(runId, AgentRunStatus.ABORTED);
            throw new DouyinBrowserException("RUN_CANCELLED", "Run cancellation requested");
        }
    }

    private void safeFinishFailed(Long runId, String code, String message) {
        try {
            runKernel.finishFailed(runId, code, message);
        } catch (Exception ignored) {
            // If cancellation already moved the run to a terminal state, do not mask the original error.
        }
    }

    private String policyTagsFor(String type) {
        if ("browser.act".equals(type)) {
            return "browser,social_follow,dm_draft";
        }
        if ("browser.extract".equals(type)) {
            return "browser,public_extract";
        }
        return "browser";
    }

    private String safeKey(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private Map<String, Object> collectionPayload(int videoIndex, CommentCollectionResult collection) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("videoIndex", videoIndex);
        payload.put("commentsCollected", collection.comments().size());
        payload.put("declaredCommentCount", collection.declaredCommentCount());
        payload.put("complete", collection.complete());
        payload.put("scrollAttempts", collection.scrollAttempts());
        payload.put("stopReason", collection.stopReason());
        payload.put("collectionCoverage", collection.declaredCommentCount() > 0
                ? Math.min(1.0d, collection.comments().size() / (double) collection.declaredCommentCount())
                : 0.0d);
        payload.put("remainingDeclaredComments", collection.declaredCommentCount() > 0
                ? Math.max(0, collection.declaredCommentCount() - collection.comments().size())
                : 0);
        payload.putAll(collection.metadata());
        return payload;
    }

    private DouyinLeadRunSummary summarize(int requestedVideoLimit, List<VideoRunState> videos) {
        int processed = videos.size();
        int succeeded = 0;
        int failed = 0;
        int comments = 0;
        int declared = 0;
        int matches = 0;
        int engagements = 0;
        List<Map<String, Object>> videoResults = new ArrayList<>();
        for (VideoRunState video : videos) {
            if ("succeeded".equals(video.status)) {
                succeeded++;
            } else if ("failed".equals(video.status)) {
                failed++;
            }
            comments += video.commentsCollected();
            declared += video.declaredCommentCount();
            matches += video.matches.size();
            engagements += video.engagements.size();
            videoResults.add(video.toPayload());
        }
        int remainingDeclared = declared > 0 ? Math.max(0, declared - comments) : 0;
        return new DouyinLeadRunSummary(
                requestedVideoLimit,
                processed,
                succeeded,
                failed,
                comments,
                declared,
                remainingDeclared,
                declared > 0 ? Math.min(1.0d, comments / (double) declared) : 0.0d,
                matches,
                engagements,
                videoResults);
    }

    private VideoRunState firstFailure(List<VideoRunState> videos) {
        for (VideoRunState video : videos) {
            if ("failed".equals(video.status)) {
                return video;
            }
        }
        return null;
    }

    private Map<String, Object> summaryPayload(DouyinLeadRunSummary summary) {
        return payload(
                "requestedVideoLimit", summary.requestedVideoLimit(),
                "processedVideos", summary.processedVideos(),
                "succeededVideos", summary.succeededVideos(),
                "failedVideos", summary.failedVideos(),
                "commentsCollected", summary.commentsCollected(),
                "declaredCommentCount", summary.declaredCommentCount(),
                "remainingDeclaredComments", summary.remainingDeclaredComments(),
                "collectionCoverage", summary.collectionCoverage(),
                "matchedComments", summary.matchedComments(),
                "engagementsCreated", summary.engagementsCreated(),
                "videoResults", summary.videoResults());
    }

    private Map<String, Object> payload(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return out;
    }

    private static final class VideoRunState {
        private final int index;
        private String status = "running";
        private String videoUrl = "";
        private String videoTitle = "";
        private String videoTarget = "";
        private CommentCollectionResult collection;
        private List<CommentMatchResult> matches = List.of();
        private final List<EngagementResult> engagements = new ArrayList<>();
        private String failureCode = "";
        private String failureMessage = "";

        private VideoRunState(int index) {
            this.index = index;
        }

        private String stepKey(String base) {
            return index == 0 ? base : base + "_" + (index + 1);
        }

        private int commentsCollected() {
            return collection == null ? 0 : collection.comments().size();
        }

        private int declaredCommentCount() {
            return collection == null ? 0 : collection.declaredCommentCount();
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("videoIndex", index);
            out.put("videoNumber", index + 1);
            out.put("status", status);
            out.put("url", videoUrl);
            out.put("title", videoTitle);
            out.put("target", videoTarget);
            out.put("commentsCollected", commentsCollected());
            out.put("declaredCommentCount", declaredCommentCount());
            out.put("remainingDeclaredComments", declaredCommentCount() > 0
                    ? Math.max(0, declaredCommentCount() - commentsCollected())
                    : 0);
            out.put("collectionCoverage", declaredCommentCount() > 0
                    ? Math.min(1.0d, commentsCollected() / (double) declaredCommentCount())
                    : 0.0d);
            out.put("collectionComplete", collection != null && collection.complete());
            out.put("stopReason", collection == null ? "" : collection.stopReason());
            out.put("matchedComments", matches.size());
            out.put("engagementsCreated", engagements.size());
            out.put("failureCode", failureCode);
            out.put("failureMessage", failureMessage);
            return out;
        }
    }

}
