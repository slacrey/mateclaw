package vip.mate.browser.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.action.WaitPayload;
import vip.mate.browser.edge.action.WaitSuccess;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlanExecutionServiceTest {

    private FakeActionExecutionService fakeExec;
    private PlanExecutionService planExec;
    private BrowserSession session;

    @BeforeEach
    void setup() {
        fakeExec = new FakeActionExecutionService();
        planExec = new PlanExecutionService(fakeExec);
        session = BrowserSession.builder()
                .id("sess-plan")
                .subject("alice")
                .agentVersion("0.2.0")
                .ws(mock(WebSocketSession.class))
                .lastHeartbeatAt(Instant.parse("2026-05-28T00:00:00Z"))
                .build();
    }

    @Test
    void emptyPlan_returnsImmediateSuccess() {
        var result = planExec.execute(session, List.of()).block(Duration.ofSeconds(1));
        var nullPlanResult = planExec.execute(session, null).block(Duration.ofSeconds(1));

        assertThat(result).isInstanceOf(PlanResult.Success.class);
        assertThat(((PlanResult.Success) result).completed()).isEmpty();
        assertThat(nullPlanResult).isInstanceOf(PlanResult.Success.class);
        assertThat(((PlanResult.Success) nullPlanResult).completed()).isEmpty();
        assertThat(fakeExec.callOrder()).isEmpty();
    }

    @Test
    void singleStepSuccess_returnsSuccessWithOneResult() {
        var req = waitRequest("m1");
        var success = success(12);
        fakeExec.stub(req, success, Duration.ZERO);

        var result = planExec.execute(session, List.of(req)).block(Duration.ofSeconds(1));

        assertThat(result).isInstanceOf(PlanResult.Success.class);
        assertThat(((PlanResult.Success) result).completed()).containsExactly(success);
        assertThat(fakeExec.callOrder()).containsExactly("m1");
    }

    @Test
    void twoStepPlan_executesSequentially_secondAfterFirstResolves() {
        var first = waitRequest("m1");
        var second = waitRequest("m2");
        fakeExec.stub(first, success(1), Duration.ofMillis(120));
        fakeExec.stub(second, success(2), Duration.ZERO);

        var result = planExec.execute(session, List.of(first, second)).block(Duration.ofSeconds(2));

        assertThat(result).isInstanceOf(PlanResult.Success.class);
        assertThat(fakeExec.callOrder()).containsExactly("m1", "m2");
        assertThat(fakeExec.sendTimestamps()).hasSize(2);
        assertThat(fakeExec.completionTimestamps()).hasSize(2);
        assertThat(fakeExec.sendTimestamps().get(1) - fakeExec.sendTimestamps().get(0))
                .isGreaterThanOrEqualTo(Duration.ofMillis(100).toNanos());
        assertThat(fakeExec.sendTimestamps().get(1))
                .isGreaterThanOrEqualTo(fakeExec.completionTimestamps().get(0));
    }

    @Test
    void onFirstFailure_stopsAndReturnsPartial_doesNotSendRemainingSteps() {
        var first = waitRequest("m1");
        var second = waitRequest("m2");
        var failure = new ActionResult.Failure("TARGET_MISSING", "target not found", false);
        fakeExec.stub(first, failure, Duration.ZERO);
        fakeExec.stub(second, success(2), Duration.ZERO);

        var result = planExec.execute(session, List.of(first, second)).block(Duration.ofSeconds(1));

        assertThat(result).isInstanceOf(PlanResult.Partial.class);
        var partial = (PlanResult.Partial) result;
        assertThat(partial.completed()).isEmpty();
        assertThat(partial.failed()).isEqualTo(failure);
        assertThat(fakeExec.callOrder()).containsExactly("m1");
    }

    @Test
    void onMidPlanFailure_partialCarriesEarlierSuccesses() {
        var first = waitRequest("m1");
        var second = waitRequest("m2");
        var third = waitRequest("m3");
        var firstSuccess = success(1);
        var failure = new ActionResult.Failure("CLICK_BLOCKED", "element covered", true);
        fakeExec.stub(first, firstSuccess, Duration.ZERO);
        fakeExec.stub(second, failure, Duration.ZERO);
        fakeExec.stub(third, success(3), Duration.ZERO);

        var result = planExec.execute(session, List.of(first, second, third)).block(Duration.ofSeconds(1));

        assertThat(result).isInstanceOf(PlanResult.Partial.class);
        var partial = (PlanResult.Partial) result;
        assertThat(partial.completed()).containsExactly(firstSuccess);
        assertThat(partial.failed()).isEqualTo(failure);
        assertThat(fakeExec.callOrder()).containsExactly("m1", "m2");
    }

    @Test
    void noParallelDispatch_invariant() {
        var plan = List.of(
                waitRequest("m1"),
                waitRequest("m2"),
                waitRequest("m3"),
                waitRequest("m4"),
                waitRequest("m5"));
        for (var req : plan) {
            fakeExec.stub(req, success(50), Duration.ofMillis(50));
        }

        long startedAt = System.nanoTime();
        var result = planExec.execute(session, plan).block(Duration.ofSeconds(2));
        long elapsedNanos = System.nanoTime() - startedAt;

        assertThat(result).isInstanceOf(PlanResult.Success.class);
        assertThat(fakeExec.callOrder()).containsExactly("m1", "m2", "m3", "m4", "m5");
        assertThat(elapsedNanos).isGreaterThanOrEqualTo(Duration.ofMillis(250).toNanos());
    }

    private static ActionRequest waitRequest(String msgId) {
        return new ActionRequest(
                msgId,
                new TabRef.Main(),
                ActionKind.WAIT,
                new WaitPayload("time", 1L, null, null),
                5_000);
    }

    private static ActionResult.Success success(long elapsedMs) {
        return new ActionResult.Success(elapsedMs, new WaitSuccess(elapsedMs));
    }

    private static final class FakeActionExecutionService extends ActionExecutionService {
        private final Map<String, StubbedAction> stubs = new ConcurrentHashMap<>();
        private final List<String> callOrder = new CopyOnWriteArrayList<>();
        private final List<Long> sendTimestamps = new CopyOnWriteArrayList<>();
        private final List<Long> completionTimestamps = new CopyOnWriteArrayList<>();

        private FakeActionExecutionService() {
            super(mock(BrowserSessionRegistry.class), new ObjectMapper(), Clock.systemUTC());
        }

        void stub(ActionRequest req, ActionResult result, Duration delay) {
            stubs.put(req.msgId(), new StubbedAction(result, delay));
        }

        List<String> callOrder() {
            return List.copyOf(callOrder);
        }

        List<Long> sendTimestamps() {
            return List.copyOf(sendTimestamps);
        }

        List<Long> completionTimestamps() {
            return List.copyOf(completionTimestamps);
        }

        @Override
        public Mono<ActionResult> execute(BrowserSession session, ActionRequest req) {
            callOrder.add(req.msgId());
            sendTimestamps.add(System.nanoTime());
            var stub = stubs.getOrDefault(req.msgId(),
                    new StubbedAction(success(1), Duration.ZERO));
            Mono<ActionResult> result = stub.delay().isZero()
                    ? Mono.just(stub.result())
                    : Mono.delay(stub.delay()).map(ignored -> stub.result());
            return result.doOnNext(ignored -> completionTimestamps.add(System.nanoTime()));
        }

        private record StubbedAction(ActionResult result, Duration delay) {
        }
    }
}
