package vip.mate.lead.douyin;

import org.springframework.stereotype.Service;
import vip.mate.lead.douyin.browser.DouyinBrowserAdapter;
import vip.mate.lead.douyin.browser.DouyinBrowserException;
import vip.mate.lead.douyin.match.CommentMatcher;
import vip.mate.lead.douyin.model.CommentCollectionResult;
import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
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
        CommentCollectionResult collection = null;
        List<CommentMatchResult> matches = List.of();
        List<EngagementResult> engagements = new ArrayList<>();
        try {
            runKernel.startRun(runId);
            step(runId, "open_douyin_search", "browser.act",
                    () -> browser.openDouyinAndSearch(input));
            step(runId, "apply_sort", "browser.act",
                    () -> browser.applySort(input));
            DouyinBrowserAdapter.BrowserObservation openedVideo = step(runId, "open_first_video", "browser.act",
                    () -> browser.openVideo(0));
            events.publish(new RunEvent(runId, null, "lead.video.opened", "info", Map.of(
                    "url", openedVideo.url(),
                    "title", openedVideo.title(),
                    "target", openedVideo.message() == null ? "" : openedVideo.message()), null));
            DouyinBrowserAdapter.BrowserObservation openedComments = step(runId, "open_comments", "browser.act",
                    browser::openComments);
            events.publish(new RunEvent(runId, null, "lead.comments.opened", "info", Map.of(
                    "url", openedComments.url(),
                    "code", openedComments.code() == null ? "" : openedComments.code(),
                    "method", openedComments.message() == null ? "" : openedComments.message()), null));
            DouyinBrowserAdapter.RegionInfo region = step(runId, "detect_comment_region", "browser.observe",
                    browser::detectCommentRegion);
            events.publish(new RunEvent(runId, null, "lead.comments.region_detected", "info", Map.of(
                    "regionKey", region.regionKey(),
                    "x", region.x(),
                    "y", region.y(),
                    "width", region.width(),
                    "height", region.height(),
                    "safeX", region.safeX(),
                    "safeY", region.safeY(),
                    "source", region.source()), null));
            collection = step(runId, "collect_all_comments", "browser.extract",
                    () -> browser.collectAllComments(region));
            persistence.saveComments(taskId, runId, collection.comments());
            Map<String, Object> collectionPayload = new java.util.LinkedHashMap<>();
            collectionPayload.put("commentsCollected", collection.comments().size());
            collectionPayload.put("declaredCommentCount", collection.declaredCommentCount());
            collectionPayload.put("complete", collection.complete());
            collectionPayload.put("scrollAttempts", collection.scrollAttempts());
            collectionPayload.put("stopReason", collection.stopReason());
            collectionPayload.put("collectionCoverage", collection.declaredCommentCount() > 0
                    ? Math.min(1.0d, collection.comments().size() / (double) collection.declaredCommentCount())
                    : 0.0d);
            collectionPayload.put("remainingDeclaredComments", collection.declaredCommentCount() > 0
                    ? Math.max(0, collection.declaredCommentCount() - collection.comments().size())
                    : 0);
            collectionPayload.putAll(collection.metadata());
            events.publish(new RunEvent(runId, null, "lead.comments.collected", "info", collectionPayload, null));
            if (!collection.complete()) {
                throw new DouyinBrowserException("COMMENT_COLLECTION_INCOMPLETE",
                        "评论未完整采集: declared=" + collection.declaredCommentCount()
                                + ", collected=" + collection.comments().size()
                                + ", stopReason=" + collection.stopReason());
            }

            var collectedComments = collection.comments();
            matches = step(runId, "match_comment_text", "llm.classify.batch",
                    () -> matcher.matched(collectedComments, input.commentMatchRule()));
            persistence.markMatches(taskId, matches);
            events.publish(new RunEvent(runId, null, "lead.comment.matched", "info", Map.of(
                    "matchedComments", matches.size(),
                    "rule", input.commentMatchRule()), null));
            if (!input.engage()) {
                events.publish(new RunEvent(runId, null, "lead.engagement.skipped", "info", Map.of(
                        "reason", "engagement_disabled",
                        "matchedComments", matches.size()), null));
                persistence.completeTask(taskId, "succeeded", collection, matches, engagements);
                runKernel.finishSucceeded(runId, "lead-task:" + taskId);
                return;
            }
            if (matches.isEmpty()) {
                throw new DouyinBrowserException("COMMENT_MATCH_NOT_FOUND",
                        "评论区已采集，但没有命中目标评论: " + input.commentMatchRule());
            }

            for (CommentMatchResult match : matches) {
                assertNotCancelled(runId);
                EngagementResult engagement = step(runId,
                        "engage_matched_comment_author_" + safeKey(match.comment().commentKey()),
                        "browser.act",
                        () -> browser.followAndDraft(match.comment(), input.dmDraft(), input.sendDm()));
                engagements.add(engagement);
                LeadProfileEntity profile = persistence.saveProfile(taskId, runId, engagement);
                Long commentId = persistence.findCommentId(taskId, match.comment().commentKey());
                persistence.saveEngagement(taskId, runId, profile.getId(), commentId,
                        "dm_draft", engagement, input.dmDraft());
                events.publish(new RunEvent(runId, null, "lead.engagement.completed", "info", Map.of(
                        "author", engagement.author(),
                        "commentKey", match.comment().commentKey(),
                        "followConfirmed", engagement.followConfirmed(),
                        "dmOpened", engagement.dmOpened(),
                        "draftTyped", engagement.draftTyped(),
                        "sent", engagement.sent(),
                        "status", engagement.status()), null));
                if (!engagement.draftTyped()) {
                    throw new DouyinBrowserException(
                            engagement.failureCode() == null ? "ENGAGEMENT_FAILED" : engagement.failureCode(),
                            engagement.failureMessage() == null ? "互动执行未完成" : engagement.failureMessage());
                }
            }

            persistence.completeTask(taskId, "succeeded", collection, matches, engagements);
            runKernel.finishSucceeded(runId, "lead-task:" + taskId);
        } catch (Exception e) {
            String code = e instanceof DouyinBrowserException dbe ? dbe.code() : "DOUYIN_RUN_FAILED";
            String message = e.getMessage() == null ? code : e.getMessage();
            persistence.completeTask(taskId, "failed", collection, matches, engagements);
            safeFinishFailed(runId, code, message);
            events.publish(new RunEvent(runId, null, "lead.run.failed", "error",
                    Map.of("code", code, "message", message), null));
        } finally {
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

}
