package vip.mate.browser.orchestrator.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;
import vip.mate.browser.orchestrator.screenshot.PageScreenshot;
import vip.mate.browser.orchestrator.screenshot.ScreenshotEdgeClient;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for the Wave 3-A2 real {@link VisionEngine}.
 *
 * <p>The engine consumes a {@link PageScreenshot} from the
 * {@link ScreenshotEdgeClient} port, asks a vision-capable LLM for a JSON
 * grounding response, and returns a {@link GroundingResult}. Tests pin:
 * <ul>
 *   <li>The P0-3-A2 confidence threshold (must not promote below 0.6).</li>
 *   <li>Hint-shape routing (ByRefId is not Vision's job).</li>
 *   <li>Graceful degradation when the vision model is unconfigured.</li>
 *   <li>Robustness against malformed LLM output.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class VisionEngineTest {

    private static final byte[] FAKE_PNG = "fake-png".getBytes(StandardCharsets.UTF_8);
    private static final Viewport VP = new Viewport(1280, 800);

    private ScreenshotEdgeClient screenshotClient;
    private ProviderChatModelFactory chatModelFactory;
    private RetryTemplate retryTemplate;
    private ObjectMapper mapper;
    private ModelConfigResolver modelResolver;
    private SetOfMarkAnnotator setOfMarkAnnotator;
    private VisionEngine engine;

    private BrowserSession session;
    private TabRef tabRef;
    private PageSnapshot snapshot;
    private PageScreenshot screenshot;

    @BeforeEach
    void setUp() {
        screenshotClient = mock(ScreenshotEdgeClient.class);
        chatModelFactory = mock(ProviderChatModelFactory.class);
        retryTemplate = RetryTemplate.builder().maxAttempts(1).build();
        mapper = new ObjectMapper();
        modelResolver = mock(ModelConfigResolver.class);
        // Real annotator: it is a pure transform with no collaborators. With the
        // FAKE_PNG fixture ImageIO cannot decode the bytes, so annotate() simply
        // returns the original bytes unchanged — exactly the robust no-throw
        // contract we want exercised here. SoM candidate selection / prompting
        // is driven entirely by the snapshot, independent of the (undecodable)
        // image, so it still runs.
        setOfMarkAnnotator = new SetOfMarkAnnotator();
        engine = new VisionEngine(
                screenshotClient, chatModelFactory, retryTemplate, mapper, modelResolver, setOfMarkAnnotator);

        session = BrowserSession.builder()
                .id("sess-vision")
                .subject("alice")
                .agentVersion("0.2.0")
                .ws(null)
                .lastHeartbeatAt(java.time.Instant.EPOCH)
                .build();
        tabRef = new TabRef.Main();
        snapshot = PageSnapshot.fromA11yText(
                "Button[ref=ref_1]: Submit @{100,200 80x32}",
                VP);
        screenshot = new PageScreenshot(
                "shot-1", 1730000000L, 42L, FAKE_PNG, VP, VP);
    }

    @Test
    void name_isVision() {
        assertThat(engine.name()).isEqualTo("vision");
    }

    // -----------------------------------------------------------------
    // Hint-shape routing
    // -----------------------------------------------------------------

    @Test
    void byRefIdHint_doesNotInvokeVision_returnsMiss() {
        var hint = new GroundingHint.ByRefId("ref_99");

        var result = engine.ground(session, tabRef, snapshot, hint);

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason()).contains("ByRefId"));
        verify(screenshotClient, never()).request(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(chatModelFactory, never()).buildFor(any(), any());
    }

    // -----------------------------------------------------------------
    // Model resolution
    // -----------------------------------------------------------------

    @Test
    void noVisionModelConfigured_returnsMiss_withTodoMessage() {
        when(modelResolver.resolveVisionModel()).thenReturn(Optional.empty());

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive");
        var result = engine.ground(session, tabRef, snapshot, hint);

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason())
                        .contains("vision")
                        .contains("no model configured"));
        verify(screenshotClient, never()).request(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // -----------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------

    @Test
    void happyPath_hitWithHighConfidence_returnsHit() {
        givenModelAndScreenshot();
        stubLlmJson("{ \"found\": true, \"x\": 100, \"y\": 200, \"w\": 80, \"h\": 32, "
                + "\"confidence\": 0.92, \"reason\": \"button matches\" }");

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive");
        var result = engine.ground(session, tabRef, snapshot, hint);

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class, hit -> {
            assertThat(hit.target().bbox().x()).isEqualTo(100);
            assertThat(hit.target().bbox().y()).isEqualTo(200);
            assertThat(hit.target().bbox().w()).isEqualTo(80);
            assertThat(hit.target().bbox().h()).isEqualTo(32);
            assertThat(hit.evidence())
                    .contains("vision")
                    .contains("0.92");
        });
        verify(screenshotClient).request(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // -----------------------------------------------------------------
    // Set-of-Mark path (Wave 3-A3) — runs before the coord path
    // -----------------------------------------------------------------

    /** Two clickable candidates in document order: ref_1 (#1), ref_2 (#2). */
    private static final PageSnapshot SOM_SNAPSHOT = PageSnapshot.fromA11yText(
            "Button[ref=ref_1]: Search @{10,20 40x16}\n"
                    + "Link[ref=ref_2]: Profile @{200,400 120x18}",
            VP);

    @Test
    void setOfMark_returnsChosenNumber_hitsThatCandidatesBboxAndRefId() {
        givenModelAndScreenshot();
        // SoM picks #2; coord path is stubbed too but must NOT be reached.
        stubSomThenCoord("{\"n\": 2}",
                "{ \"found\": true, \"x\": 999, \"y\": 999, \"w\": 1, \"h\": 1, \"confidence\": 0.99 }");

        var hint = new GroundingHint.A11yMatch("link", Pattern.compile("Profile"), "interactive");
        var result = engine.ground(session, tabRef, SOM_SNAPSHOT, hint);

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class, hit -> {
            // Maps to the 2nd candidate (ref_2 / Profile), NOT the coord bbox.
            assertThat(hit.target().bbox().x()).isEqualTo(200);
            assertThat(hit.target().bbox().y()).isEqualTo(400);
            assertThat(hit.target().bbox().w()).isEqualTo(120);
            assertThat(hit.target().bbox().h()).isEqualTo(18);
            assertThat(hit.target().refId()).isEqualTo("ref_2");
            assertThat(hit.evidence()).contains("set-of-mark").contains("#2");
        });
        // Screenshot fetched exactly once and reused across both attempts.
        verify(screenshotClient).request(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void setOfMark_returnsMinusOne_fallsThroughToCoordPath() {
        givenModelAndScreenshot();
        // SoM declines (-1) → engine must fall through to the coord prompt,
        // whose JSON grounds the click. Proves the coord path stays intact.
        stubSomThenCoord("{\"n\": -1}",
                "{ \"found\": true, \"x\": 100, \"y\": 200, \"w\": 80, \"h\": 32, "
                        + "\"confidence\": 0.9, \"reason\": \"coord fallback\" }");

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Search"), "interactive");
        var result = engine.ground(session, tabRef, SOM_SNAPSHOT, hint);

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Hit.class, hit -> {
            // Coord-path bbox (no refId), and the coord-path evidence shape.
            assertThat(hit.target().bbox().x()).isEqualTo(100);
            assertThat(hit.target().bbox().y()).isEqualTo(200);
            assertThat(hit.target().bbox().w()).isEqualTo(80);
            assertThat(hit.target().bbox().h()).isEqualTo(32);
            assertThat(hit.target().refId()).isNull();
            assertThat(hit.evidence()).contains("vision").contains("0.9");
        });
    }

    @Test
    void setOfMark_outOfRangeNumber_fallsThroughToCoordPath() {
        givenModelAndScreenshot();
        // Only 2 candidates exist; an n past the range must not index OOB —
        // it falls through to the coord path instead.
        stubSomThenCoord("{\"n\": 9}",
                "{ \"found\": false, \"confidence\": 0.0, \"reason\": \"nothing matched\" }");

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Search"), "interactive");
        var result = engine.ground(session, tabRef, SOM_SNAPSHOT, hint);

        // Coord path then reports found=false → Miss (not a Hit, not an OOB throw).
        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason()).contains("nothing matched"));
    }

    // -----------------------------------------------------------------
    // P0-3-A2: confidence threshold
    // -----------------------------------------------------------------

    @Test
    void lowConfidenceResponse_doesNotPromoteToHit() {
        givenModelAndScreenshot();
        stubLlmJson("{ \"found\": true, \"x\": 100, \"y\": 200, \"w\": 80, \"h\": 32, "
                + "\"confidence\": 0.45, \"reason\": \"unsure\" }");

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive");
        var result = engine.ground(session, tabRef, snapshot, hint);

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class, miss -> {
            assertThat(miss.reason())
                    .contains("low confidence")
                    .contains("0.45");
        });
    }

    @Test
    void exactlyAtConfidenceThreshold_promotesToHit() {
        givenModelAndScreenshot();
        // 0.6 is the threshold; >= 0.6 should hit.
        stubLlmJson("{ \"found\": true, \"x\": 100, \"y\": 200, \"w\": 80, \"h\": 32, "
                + "\"confidence\": 0.6, \"reason\": \"boundary\" }");

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive");
        var result = engine.ground(session, tabRef, snapshot, hint);

        assertThat(result).isInstanceOf(GroundingResult.Hit.class);
    }

    // -----------------------------------------------------------------
    // found=false / parse errors
    // -----------------------------------------------------------------

    @Test
    void llmReportsFoundFalse_returnsMiss() {
        givenModelAndScreenshot();
        stubLlmJson("{ \"found\": false, \"confidence\": 0.0, \"reason\": \"target not visible\" }");

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive");
        var result = engine.ground(session, tabRef, snapshot, hint);

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason())
                        .contains("vision")
                        .contains("target not visible"));
    }

    @Test
    void malformedLlmJson_returnsMiss_doesNotThrow() {
        givenModelAndScreenshot();
        stubLlmText("not actually json {{{ broken");

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive");
        var result = engine.ground(session, tabRef, snapshot, hint);

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason()).contains("parse"));
    }

    @Test
    void llmJsonWrappedInCodeFence_isStillParsed() {
        givenModelAndScreenshot();
        // Robustness — many models will emit ```json prefix even when told not to.
        stubLlmText("```json\n{ \"found\": true, \"x\": 10, \"y\": 20, \"w\": 5, \"h\": 5, "
                + "\"confidence\": 0.9, \"reason\": \"ok\" }\n```");

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive");
        var result = engine.ground(session, tabRef, snapshot, hint);

        assertThat(result).isInstanceOf(GroundingResult.Hit.class);
    }

    // -----------------------------------------------------------------
    // ScreenshotEdgeClient errors
    // -----------------------------------------------------------------

    @Test
    void screenshotClientError_returnsMiss_notException() {
        when(modelResolver.resolveVisionModel()).thenReturn(Optional.of(visionModel()));
        when(screenshotClient.request(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Mono.error(new RuntimeException("WS dead")));

        var hint = new GroundingHint.A11yMatch("button", Pattern.compile("Submit"), "interactive");
        var result = engine.ground(session, tabRef, snapshot, hint);

        assertThat(result).isInstanceOfSatisfying(GroundingResult.Miss.class,
                miss -> assertThat(miss.reason()).contains("screenshot"));
    }

    // -----------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------

    /** Stub model resolution + screenshot fetch with the canned PageScreenshot. */
    private void givenModelAndScreenshot() {
        when(modelResolver.resolveVisionModel()).thenReturn(Optional.of(visionModel()));
        when(screenshotClient.request(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Mono.just(screenshot));
    }

    private ModelConfigEntity visionModel() {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setId(101L);
        m.setName("test-vision");
        m.setProvider("dashscope");
        m.setModelName("qwen-vl-plus");
        return m;
    }

    /**
     * Stub the {@link ProviderChatModelFactory} -> {@link ChatModel} ->
     * {@link ChatClient} chain to return {@code content} for the <em>coord</em>
     * grounding prompt. We intercept at the
     * {@link ChatModel#call(org.springframework.ai.chat.prompt.Prompt)} boundary
     * since {@link ChatClient#create} delegates to it; that lets us keep this
     * fixture free of Spring AI mock-builder gymnastics.
     *
     * <p>Since Wave 3-A3 the Set-of-Mark attempt runs first and also calls
     * {@code ChatModel.call}. To keep the legacy coord-path assertions intact,
     * the SoM prompt is auto-answered with a non-matching {@code {"n":-1}} so
     * the engine falls through to the coord prompt, where {@code content} is
     * returned. Tests that exercise SoM directly use
     * {@link #stubSomThenCoord(String, String)}.
     */
    private void stubLlmText(String content) {
        stubSomThenCoord("{\"n\": -1}", content);
    }

    private void stubLlmJson(String json) {
        stubLlmText(json);
    }

    /**
     * Route the two distinct vision prompts to distinct canned replies based on
     * a marker substring unique to each prompt template:
     * <ul>
     *   <li>SoM prompt — contains "labeled with a number" → {@code somReply}</li>
     *   <li>coord prompt — contains "grounding a click target" → {@code coordReply}</li>
     * </ul>
     * Order-independent, so a test reads cleanly regardless of which prompt the
     * engine issues first.
     */
    private void stubSomThenCoord(String somReply, String coordReply) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenAnswer(invocation -> {
                    org.springframework.ai.chat.prompt.Prompt prompt = invocation.getArgument(0);
                    String text = prompt.getInstructions().stream()
                            .map(org.springframework.ai.chat.messages.Message::getText)
                            .filter(java.util.Objects::nonNull)
                            .reduce("", (a, b) -> a + "\n" + b);
                    String reply = text.contains("labeled with a number") ? somReply : coordReply;
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
                });
        when(chatModelFactory.buildFor(any(), any())).thenReturn(chatModel);
    }
}
