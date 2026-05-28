package vip.mate.browser.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.orchestrator.ActionExecutionService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test recording sink standing in for the Native Host + Extension half of the
 * Edge transport.
 *
 * <p><b>Design choice — observe at the {@link WebSocketSession#sendMessage}
 * seam (Option A in the task spec).</b> The production
 * {@link ActionExecutionService} sends every outbound envelope via
 * {@code session.getWs().sendMessage(TextMessage)} and consumes inbound
 * results via {@link ActionExecutionService#deliverResult(String, ActionResult)}.
 * That gives us a single, narrow seam to intercept — we don't need to replace
 * any Spring bean or modify production code. The sink hands out a Mockito-
 * stubbed {@link WebSocketSession} via {@link #mockWs(String)}; the test
 * passes that mock to {@link vip.mate.browser.edge.session.BrowserSessionRegistry#register},
 * and from then on every outbound envelope on that session lands in the sink.
 *
 * <p>We considered Option B (a replacement bean side-channel), but Option A
 * keeps the test fixture local to a single class without weaving a recording
 * decorator around {@link ActionExecutionService}, and keeps the integration
 * surface identical to production (the orchestrator does not know it is
 * talking to a mock).
 *
 * <p>The sink is provided as a {@code @Bean} inside the test's
 * {@code @TestConfiguration}, not as a {@code @TestComponent}, so the wiring
 * is explicit and the bean cannot accidentally leak into a production
 * {@code @SpringBootTest}.
 */
public class RecordingWsSink {

    private final ActionExecutionService actionExec;
    private final ObjectMapper mapper;

    /** Every captured outbound envelope, in send order. */
    private final List<EdgeMessage> envelopes = new CopyOnWriteArrayList<>();
    /** {@code System.currentTimeMillis()} for each captured envelope, same order as {@link #envelopes}. */
    private final List<Long> timestamps = new CopyOnWriteArrayList<>();
    /** Per-kind handler invoked synchronously inside the capture path. */
    private final ConcurrentHashMap<String, Consumer<EdgeMessage>> handlers = new ConcurrentHashMap<>();

    /** ++ on {@code action.execute} send, -- on the matching reply. */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** Delayed-reply scheduler — daemon so tests don't hang on JVM shutdown. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(daemonFactory());

    public RecordingWsSink(ActionExecutionService actionExec, ObjectMapper mapper) {
        this.actionExec = actionExec;
        this.mapper = mapper;
    }

    /**
     * Reset all state between tests. Called from {@code @BeforeEach}.
     * Handlers, recordings, and the in-flight counter all clear; the
     * scheduler is reused across tests.
     */
    public void reset() {
        envelopes.clear();
        timestamps.clear();
        handlers.clear();
        inFlight.set(0);
        maxInFlight.set(0);
    }

    /** Captured envelopes in send order (live, copy-on-write). */
    public List<EdgeMessage> envelopes() {
        return List.copyOf(envelopes);
    }

    /** Wall-clock timestamp of each capture, same indexing as {@link #envelopes()}. */
    public List<Long> timestamps() {
        return List.copyOf(timestamps);
    }

    /** Peak in-flight count seen during this test. */
    public int maxConcurrentInFlight() {
        return maxInFlight.get();
    }

    /**
     * Register a handler invoked synchronously on each captured envelope
     * whose wire kind matches {@code kind} (e.g. {@code "action.execute"}).
     * Last writer wins per kind — overwriting is intentional so a test can
     * change behaviour mid-run.
     */
    public void onEnvelope(String kind, Consumer<EdgeMessage> handler) {
        handlers.put(kind, handler);
    }

    /**
     * Inject a reply for an outbound envelope. Forwards to
     * {@link ActionExecutionService#deliverResult(String, ActionResult)}
     * and decrements the in-flight counter.
     *
     * <p><b>Order matters: decrement BEFORE deliverResult.</b> Reactor's
     * {@code concatMap} runs its downstream synchronously on the same thread
     * that completed the upstream Mono — so {@code deliverResult} completes
     * the move's future, which immediately fires the click {@code execute},
     * which lands in {@link #capture} on the SAME stack. If we decremented
     * after deliverResult, the click's {@code inFlight++} would see the
     * still-incremented move counter and report {@code maxInFlight == 2}
     * even though the actions are strictly sequential.
     */
    public void reply(EdgeMessage outbound, ActionResult result) {
        inFlight.decrementAndGet();
        actionExec.deliverResult(outbound.getMsgId(), result);
    }

    /** Wire kind of the last captured envelope, or {@code null} if none yet. */
    public String lastEnvelopeKind() {
        if (envelopes.isEmpty()) {
            return null;
        }
        EdgeMessageKind kind = envelopes.get(envelopes.size() - 1).getKind();
        return kind == null ? null : kind.wire();
    }

    /** Scheduler for delayed-reply tests (e.g. the 180ms cursor transition). */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /**
     * Build a Mockito-stubbed {@link WebSocketSession} whose
     * {@code sendMessage(TextMessage)} feeds back into this sink's capture
     * path. {@code getId()} returns {@code wsId} and {@code isOpen()} returns
     * {@code true} — the minimum surface the
     * {@link vip.mate.browser.edge.session.BrowserSessionRegistry} touches in
     * normal flow.
     */
    public WebSocketSession mockWs(String wsId) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(wsId);
        when(ws.isOpen()).thenReturn(true);
        try {
            doAnswer(new SendMessageAnswer()).when(ws).sendMessage(any(WebSocketMessage.class));
        } catch (Exception e) {
            // sendMessage declares IOException; Mockito's when() never
            // actually invokes the method, but the compiler can't tell.
            throw new AssertionError(e);
        }
        return ws;
    }

    /**
     * Mockito-style ANY matcher proxy — kept local so test classes don't
     * have to import {@code org.mockito.ArgumentMatchers.any}.
     */
    @SuppressWarnings("unchecked")
    private static <T> T any(Class<T> type) {
        return org.mockito.ArgumentMatchers.any(type);
    }

    /**
     * Mockito {@code Answer} that turns every {@code sendMessage(TextMessage)}
     * call into a sink capture. Lives as a named inner class purely to make
     * stack traces readable when a handler throws.
     */
    private final class SendMessageAnswer implements Answer<Void> {
        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
            WebSocketMessage<?> message = invocation.getArgument(0);
            if (!(message instanceof TextMessage text)) {
                // Binary frames aren't on the wire format yet (v1.1); a future
                // protocol bump that introduces them should extend this sink.
                return null;
            }
            capture(text);
            return null;
        }
    }

    /**
     * Parse a {@link TextMessage} into an {@link EdgeMessage} and run the
     * sink's bookkeeping + handler dispatch. Public-visibility-wise this is
     * package-private so tests in this package can drive it directly if they
     * ever need to (current tests use {@link #mockWs(String)} exclusively).
     */
    void capture(TextMessage text) {
        EdgeMessage env;
        try {
            env = mapper.readValue(text.getPayload(), EdgeMessage.class);
        } catch (Exception e) {
            throw new AssertionError("RecordingWsSink: malformed outbound envelope: "
                    + text.getPayload(), e);
        }
        envelopes.add(env);
        timestamps.add(System.currentTimeMillis());

        if (env.getKind() == EdgeMessageKind.ACTION_EXECUTE) {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
        }

        String wireKind = env.getKind() == null ? null : env.getKind().wire();
        if (wireKind != null) {
            Consumer<EdgeMessage> handler = handlers.get(wireKind);
            if (handler != null) {
                handler.accept(env);
            }
        }
    }

    /**
     * Payload-keyed view of the last captured envelope's payload — used by a
     * few assertions for readability. Returns an empty map if no envelope has
     * landed.
     */
    public Map<String, Object> lastPayload() {
        if (envelopes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> p = envelopes.get(envelopes.size() - 1).getPayload();
        return p == null ? Map.of() : p;
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "recording-ws-sink-scheduler");
            thread.setDaemon(true);
            return thread;
        };
    }
}
