package vip.mate.lead.douyin.api;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.channel.web.Utf8SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DouyinLeadAcquisitionEventStreamService {

    private static final long STREAM_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final DouyinLeadAcquisitionQueryService queryService;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    public DouyinLeadAcquisitionEventStreamService(DouyinLeadAcquisitionQueryService queryService) {
        this.queryService = queryService;
    }

    public SseEmitter stream(Long runId, String lastEventIdHeader, Long afterEventId) {
        Utf8SseEmitter emitter = new Utf8SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        long initialCursor = initialCursor(lastEventIdHeader, afterEventId);
        worker.submit(() -> pump(runId, initialCursor, emitter, closed));
        return emitter;
    }

    private void pump(Long runId, long initialCursor, SseEmitter emitter, AtomicBoolean closed) {
        long cursor = initialCursor;
        long nextHeartbeatAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS;
        try {
            while (!closed.get()) {
                for (RunTimelineEventDTO event : queryService.eventsSince(runId, cursor)) {
                    if (closed.get()) {
                        return;
                    }
                    send(emitter, event.type(), event.id(), event);
                    cursor = Math.max(cursor, parsePositiveLong(event.id(), cursor));
                }

                String status = queryService.runStatus(runId);
                if (queryService.isRunTerminal(runId)) {
                    DouyinLeadAcquisitionRunResponse snapshot = queryService.byRun(runId);
                    send(emitter, "run_snapshot", cursor > 0 ? String.valueOf(cursor) : null, snapshot);
                    send(emitter, "done", null, Map.of(
                            "runId", String.valueOf(runId),
                            "status", status == null ? "" : status,
                            "lastEventId", cursor > 0 ? String.valueOf(cursor) : ""));
                    complete(emitter, closed);
                    return;
                }

                long now = System.currentTimeMillis();
                if (now >= nextHeartbeatAt) {
                    send(emitter, "heartbeat", null, Map.of(
                            "runId", String.valueOf(runId),
                            "status", status == null ? "" : status,
                            "lastEventId", cursor > 0 ? String.valueOf(cursor) : "",
                            "ts", Instant.now().toString()));
                    nextHeartbeatAt = now + HEARTBEAT_INTERVAL_MS;
                }

                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            complete(emitter, closed);
        } catch (Exception e) {
            completeWithError(emitter, closed, e);
        }
    }

    private long initialCursor(String lastEventIdHeader, Long afterEventId) {
        long headerCursor = parsePositiveLong(lastEventIdHeader, 0L);
        long queryCursor = afterEventId == null ? 0L : Math.max(0L, afterEventId);
        return Math.max(headerCursor, queryCursor);
    }

    private long parsePositiveLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void send(SseEmitter emitter, String name, String id, Object data) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(name).data(data);
        if (id != null && !id.isBlank()) {
            event.id(id);
        }
        emitter.send(event);
    }

    private void complete(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private void completeWithError(SseEmitter emitter, AtomicBoolean closed, Exception e) {
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(e);
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
