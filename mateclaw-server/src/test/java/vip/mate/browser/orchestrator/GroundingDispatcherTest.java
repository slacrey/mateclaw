package vip.mate.browser.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;
import vip.mate.browser.orchestrator.engine.A11yEngine;
import vip.mate.browser.orchestrator.engine.DomEngine;
import vip.mate.browser.orchestrator.engine.VisionEngine;
import vip.mate.browser.orchestrator.snapshot.PageSnapshotService;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroundingDispatcherTest {

    @Mock
    private DomEngine dom;

    @Mock
    private A11yEngine a11y;

    @Mock
    private VisionEngine vision;

    @Mock
    private PageSnapshotService snapshotService;

    private BrowserSession session;
    private PageSnapshot snapshot;
    private GroundingHint hint;
    private GroundingDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        session = BrowserSession.builder()
                .id("sess-1")
                .subject("alice")
                .agentVersion("0.2.0")
                .ws(null)
                .lastHeartbeatAt(java.time.Instant.EPOCH)
                .build();
        snapshot = PageSnapshot.fromA11yText(
                "Button[ref=ref_1]: Submit @{100,200 80x32}",
                new Viewport(1280, 800));
        hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive");
        dispatcher = new GroundingDispatcher(dom, a11y, vision, snapshotService);
        when(snapshotService.request(eq(session), eq(new TabRef.Main()), eq(hint.filter())))
                .thenReturn(Mono.just(snapshot));
    }

    @Test
    void domHit_returnsHit_doesNotInvokeA11yOrVision() {
        var hit = hit("dom");
        when(dom.ground(snapshot, hint)).thenReturn(hit);

        var result = dispatcher.ground(session, new TabRef.Main(), hint);

        assertThat(result).isSameAs(hit);
        verify(a11y, never()).ground(any(), any());
        verify(vision, never()).ground(any(), any());
    }

    @Test
    void domMissAllStubs_returnsMiss() {
        when(dom.ground(snapshot, hint)).thenReturn(new GroundingResult.Miss("dom miss"));
        when(a11y.ground(snapshot, hint)).thenReturn(new GroundingResult.Miss("stub-phase-2"));
        when(vision.ground(snapshot, hint)).thenReturn(new GroundingResult.Miss("stub-phase-2"));

        var result = dispatcher.ground(session, new TabRef.Main(), hint);

        assertThat(result).isEqualTo(new GroundingResult.Miss("no-engine-hit"));
    }

    @Test
    void domAmbiguous_a11yStubMiss_visionStubMiss_returnsTheFirstAmbiguous() {
        var ambiguous = ambiguous("dom", "ref_1", "ref_2");
        when(dom.ground(snapshot, hint)).thenReturn(ambiguous);
        when(a11y.ground(snapshot, hint)).thenReturn(new GroundingResult.Miss("stub-phase-2"));
        when(vision.ground(snapshot, hint)).thenReturn(new GroundingResult.Miss("stub-phase-2"));

        var result = dispatcher.ground(session, new TabRef.Main(), hint);

        assertThat(result).isSameAs(ambiguous);
    }

    @Test
    void domAmbiguous_a11yHit_returnsA11yHit_disambiguation() {
        when(dom.ground(snapshot, hint)).thenReturn(ambiguous("dom", "ref_1", "ref_2"));
        var a11yHit = hit("narrowed");
        when(a11y.ground(snapshot, hint)).thenReturn(a11yHit);

        var result = dispatcher.ground(session, new TabRef.Main(), hint);

        assertThat(result).isSameAs(a11yHit);
        verify(vision, never()).ground(any(), any());
    }

    @Test
    void domMiss_a11yAmbiguous_visionHit_returnsVisionHit() {
        when(dom.ground(snapshot, hint)).thenReturn(new GroundingResult.Miss("dom miss"));
        when(a11y.ground(snapshot, hint)).thenReturn(ambiguous("a11y", "ref_1", "ref_2"));
        var visionHit = hit("vision");
        when(vision.ground(snapshot, hint)).thenReturn(visionHit);

        var result = dispatcher.ground(session, new TabRef.Main(), hint);

        assertThat(result).isSameAs(visionHit);
    }

    @Test
    void refreshesSnapshotBeforeGround_viaPageSnapshotService() {
        when(dom.ground(snapshot, hint)).thenReturn(hit("dom"));

        dispatcher.ground(session, new TabRef.Main(), hint);

        verify(snapshotService).request(eq(session), eq(new TabRef.Main()), eq(hint.filter()));
        verify(dom).ground(snapshot, hint);
    }

    @Test
    void firstAmbiguousIsRemembered_evenIfLaterEnginesAlsoAmbiguous() {
        var domAmbiguous = ambiguous("dom", "ref_1", "ref_2");
        var a11yAmbiguous = ambiguous("a11y", "ref_3", "ref_4");
        when(dom.ground(snapshot, hint)).thenReturn(domAmbiguous);
        when(a11y.ground(snapshot, hint)).thenReturn(a11yAmbiguous);
        when(vision.ground(snapshot, hint)).thenReturn(new GroundingResult.Miss("stub-phase-2"));

        var result = dispatcher.ground(session, new TabRef.Main(), hint);

        assertThat(result).isSameAs(domAmbiguous);
    }

    private GroundingResult.Hit hit(String evidence) {
        return new GroundingResult.Hit(
                new GroundedTarget(new BBox(100, 200, 80, 32), "ref_1"),
                evidence);
    }

    private GroundingResult.Ambiguous ambiguous(String evidence, String firstRef, String secondRef) {
        return new GroundingResult.Ambiguous(List.of(
                new GroundedTarget(new BBox(100, 200, 80, 32), firstRef),
                new GroundedTarget(new BBox(300, 400, 120, 32), secondRef)),
                evidence);
    }
}
