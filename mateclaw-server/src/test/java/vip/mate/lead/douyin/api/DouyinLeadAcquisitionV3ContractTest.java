package vip.mate.lead.douyin.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DouyinLeadAcquisitionV3ContractTest {

    @Test
    void controllerExposesRunEventStreamContract() throws IOException {
        String controller = source("vip/mate/lead/douyin/api/DouyinLeadAcquisitionController.java");
        String streamService = source("vip/mate/lead/douyin/api/DouyinLeadAcquisitionEventStreamService.java");
        String combined = controller + "\n" + streamService;

        assertThat(controller).contains("@GetMapping(\"/douyin/runs\")");
        assertThat(controller).contains("@GetMapping(\"/douyin/leads\")");
        assertThat(controller).contains("@GetMapping(\"/douyin/stats\")");
        assertThat(controller).contains("@GetMapping(\"/douyin/templates\")");
        assertThat(controller).contains("@PostMapping(\"/douyin/templates\")");
        assertThat(controller).contains("@PutMapping(\"/douyin/templates/{id}\")");
        assertThat(controller).contains("@DeleteMapping(\"/douyin/templates/{id}\")");
        assertThat(controller).contains("/runs/{runId}/events/stream");
        assertThat(streamService).contains("Utf8SseEmitter");
        assertThat(controller).containsAnyOf("MediaType.TEXT_EVENT_STREAM_VALUE", "text/event-stream");
        assertThat(combined).contains("afterEventId");
        assertThat(controller).contains("Last-Event-ID");
        assertThat(streamService).contains("heartbeat");
        assertThat(streamService).contains("run_snapshot");
        assertThat(streamService).contains("done");
    }

    @Test
    void timelineEventsExposeFieldsRequiredByLivePanel() throws IOException {
        String dto = source("vip/mate/lead/douyin/api/RunTimelineEventDTO.java");

        assertThat(dto).contains("String id");
        assertThat(dto).contains("String runId");
        assertThat(dto).contains("String createTime");
        assertThat(dto).contains("String type");
        assertThat(dto).contains("String severity");
        assertThat(dto).contains("String payloadJson");
        assertThat(dto).contains("row.getId()");
        assertThat(dto).contains("row.getRunId()");
        assertThat(dto).contains("row.getCreateTime()");
    }

    @Test
    void executorPublishesBusinessFriendlyRealtimeEvents() throws IOException {
        String executor = source("vip/mate/lead/douyin/DouyinLeadAcquisitionExecutor.java");

        assertThat(executor).contains("lead.search.started");
        assertThat(executor).contains("lead.search.completed");
        assertThat(executor).contains("lead.sort.started");
        assertThat(executor).contains("lead.sort.completed");
        assertThat(executor).contains("lead.comments.collecting");
        assertThat(executor).contains("lead.engagement.started");
        assertThat(executor).contains("lead.video.started");
        assertThat(executor).contains("lead.video.completed");
        assertThat(executor).contains("lead.video.failed");
        assertThat(executor).contains("lead.run.summary");
    }

    private static String source(String relativeMainJavaPath) throws IOException {
        Path modulePath = Path.of("src/main/java").resolve(relativeMainJavaPath);
        if (Files.exists(modulePath)) {
            return Files.readString(modulePath);
        }
        Path repoPath = Path.of("mateclaw-server/src/main/java").resolve(relativeMainJavaPath);
        return Files.readString(repoPath);
    }
}
