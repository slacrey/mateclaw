package vip.mate.browser.orchestrator.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingEngine;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.screenshot.PageScreenshot;
import vip.mate.browser.orchestrator.screenshot.ScreenshotEdgeClient;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.model.ModelConfigEntity;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Vision-based grounding engine. The third (and most expensive) member of
 * the dispatcher cascade: it kicks in only when DOM + A11y both miss / are
 * ambiguous.
 *
 * <p>How it works:
 * <ol>
 *   <li>For {@link GroundingHint.ByRefId} — return Miss immediately
 *       (refid-based grounding is structural, not vision's job).</li>
 *   <li>Resolve the configured vision model via
 *       {@link ModelConfigResolver}. If unconfigured, Miss with a "no
 *       model configured" reason — the dispatcher cascade still works,
 *       Vision is just disabled in that environment.</li>
 *   <li>Issue a {@link ScreenshotEdgeClient#request} on the active session;
 *       block on the {@link reactor.core.publisher.Mono} (we're inside a
 *       synchronous {@link GroundingEngine#ground} call). The frame is
 *       fetched once and reused by both vision attempts below.</li>
 *   <li><strong>Set-of-Mark (A11yMatch only, runs first):</strong> label the
 *       current snapshot's candidate boxes on the screenshot
 *       ({@link SetOfMarkAnnotator}) and ask the VLM to pick a number. A
 *       confident pick returns a Hit carrying the candidate's exact
 *       bbox + refId. More reliable than raw coords on dense / custom UIs.
 *       Any non-hit outcome falls through to the coord path.</li>
 *   <li><strong>Coord fallback:</strong> send the screenshot + a one-shot
 *       JSON-output prompt asking for raw {@code {found,x,y,w,h,confidence}};
 *       parse the response.</li>
 *   <li>Apply the {@link #CONFIDENCE_THRESHOLD} (P0-3-A2) to the coord path:
 *       if the model reports {@code confidence < 0.6}, return Miss even when
 *       {@code found: true}.</li>
 * </ol>
 *
 * <p>Errors at any stage downgrade to {@link GroundingResult.Miss} — Vision
 * never throws out of {@code ground()} because the dispatcher cascade
 * must remain available even when the vision sub-system is broken.
 */
@Slf4j
@Component
public class VisionEngine implements GroundingEngine {

    /**
     * P0-3-A2 invariant: vision must NOT promote a candidate to Hit when the
     * model's reported confidence is below this threshold. A direct grep
     * target for the Phase 3 audit.
     */
    public static final double CONFIDENCE_THRESHOLD = 0.6;

    /** Wire-spec convention; tunable later but stable for Wave 3-A2. */
    private static final int DEFAULT_SCALE_FACTOR = 1;

    /** How long we wait on the screenshot Mono before treating it as failed. */
    private static final Duration SCREENSHOT_WAIT = Duration.ofSeconds(12);

    /**
     * Single-shot JSON prompt. We tell the model to return a one-line JSON
     * object so a regex / lenient parser can recover its body even when the
     * model wraps it in code fences (handled in {@link #extractJsonBlock}).
     */
    private static final String GROUNDING_PROMPT_TEMPLATE = """
            You are grounding a click target on a web page screenshot.
            The user wants to interact with: %s
            The viewport is %dx%d CSS pixels.

            Respond with a single JSON object on one line, no markdown:
            { "found": true|false, "x": <int>, "y": <int>, "w": <int>, "h": <int>,
              "confidence": <0.0..1.0>, "reason": "<short>" }

            If you cannot identify the target, set found=false and confidence=0.
            """;

    private final ScreenshotEdgeClient screenshotClient;
    private final ProviderChatModelFactory chatModelFactory;
    private final RetryTemplate retryTemplate;
    private final ObjectMapper mapper;
    private final ModelConfigResolver modelResolver;
    private final SetOfMarkAnnotator setOfMarkAnnotator;

    public VisionEngine(ScreenshotEdgeClient screenshotClient,
                        ProviderChatModelFactory chatModelFactory,
                        RetryTemplate retryTemplate,
                        ObjectMapper mapper,
                        ModelConfigResolver modelResolver,
                        SetOfMarkAnnotator setOfMarkAnnotator) {
        this.screenshotClient = screenshotClient;
        this.chatModelFactory = chatModelFactory;
        this.retryTemplate = retryTemplate;
        this.mapper = mapper;
        this.modelResolver = modelResolver;
        this.setOfMarkAnnotator = setOfMarkAnnotator;
    }

    @Override
    public String name() {
        return "vision";
    }

    @Override
    public GroundingResult ground(BrowserSession session,
                                  TabRef tabRef,
                                  PageSnapshot snapshot,
                                  GroundingHint hint) {
        // (1) Hint-shape routing — vision can't ground a structural refid.
        if (hint instanceof GroundingHint.ByRefId) {
            return new GroundingResult.Miss(
                    "vision: not applicable to ByRefId hints (use a11y/dom engines)");
        }

        // (2) Model resolution.
        Optional<ModelConfigEntity> maybeModel = modelResolver.resolveVisionModel();
        if (maybeModel.isEmpty()) {
            return new GroundingResult.Miss(
                    "vision: no model configured (set mateclaw.browser.vision.model-id)");
        }
        ModelConfigEntity visionModel = maybeModel.get();

        // (3) Screenshot fetch — block on the Mono since GroundingEngine is sync.
        //     Fetched once and reused by both the SoM and coord attempts.
        PageScreenshot shot;
        try {
            shot = screenshotClient.request(session, tabRef, DEFAULT_SCALE_FACTOR)
                    .block(SCREENSHOT_WAIT);
        } catch (Exception e) {
            log.debug("[vision] screenshot fetch failed: {}", e.getMessage());
            return new GroundingResult.Miss("vision: screenshot fetch failed: " + e.getMessage());
        }
        if (shot == null) {
            return new GroundingResult.Miss("vision: screenshot fetch returned null");
        }

        String intent = describeIntent(hint);

        // (4) Set-of-Mark attempt (A11yMatch only) — runs FIRST. Labels the
        //     snapshot's candidate boxes on the screenshot and asks the VLM to
        //     pick a number. A successful pick returns a Hit carrying the
        //     candidate's exact bbox + refId. Any failure (no candidates,
        //     n == -1, parse error, exception) falls through to the coord path
        //     below — SoM never short-circuits to Miss on its own.
        if (hint instanceof GroundingHint.A11yMatch) {
            try {
                GroundingResult som = attemptSetOfMark(visionModel, shot, snapshot, intent);
                if (som instanceof GroundingResult.Hit) {
                    return som;
                }
            } catch (Exception e) {
                // Defensive: attemptSetOfMark already swallows, but never let
                // SoM throw out of ground().
                log.debug("[vision] set-of-mark attempt errored, falling through to coords: {}",
                        e.getMessage());
            }
        }

        // (5) Coord LLM call — the secondary fallback, unchanged from Wave 3-A2.
        String content;
        try {
            ChatModel chatModel = chatModelFactory.buildFor(visionModel, retryTemplate);
            ChatClient client = ChatClient.create(chatModel);
            UserMessage userMessage = UserMessage.builder()
                    .text(String.format(GROUNDING_PROMPT_TEMPLATE,
                            intent, snapshot.viewport().w(), snapshot.viewport().h()))
                    // Bytes are JPEG (the extension captures JPEG to fit the WS frame);
                    // label them image/jpeg so the vision model decodes correctly. The
                    // SoM path above already uses image/jpeg — keep both consistent.
                    .media(List.of(new Media(
                            MimeType.valueOf(MimeTypeUtils.IMAGE_JPEG_VALUE),
                            new ByteArrayResource(shot.pngBytes()))))
                    .build();
            content = client.prompt()
                    .messages(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.debug("[vision] LLM call failed: {}", e.getMessage());
            return new GroundingResult.Miss("vision: LLM call failed: " + e.getMessage());
        }

        // (6) Parse + threshold check.
        return parseAndThreshold(content);
    }

    // -----------------------------------------------------------------
    // Set-of-Mark path
    // -----------------------------------------------------------------

    /** Cap on how many candidate marks we draw — keeps the image readable. */
    private static final int MAX_SOM_CANDIDATES = 60;

    /**
     * Roles we treat as plausibly clickable for the first-pass candidate gate.
     * {@code generic} is included because SPAs frequently expose interactive
     * widgets (custom buttons, menu rows) as generic nodes carrying an
     * accessible name — but only when a name is present (see filter below).
     */
    private static final Set<String> CLICKABLE_ROLES = Set.of(
            "button", "link", "menuitem", "menuitemcheckbox", "menuitemradio",
            "option", "tab", "checkbox", "radio", "switch",
            "searchbox", "textbox", "combobox", "generic");

    /**
     * Prompt for the multiple-choice SoM question. We constrain the model to a
     * single one-line JSON object {@code {"n": <number>}} so the lenient parser
     * (shared with the coord path) can recover it even behind code fences.
     */
    private static final String SET_OF_MARK_PROMPT_TEMPLATE = """
            Each candidate element on the screenshot is labeled with a number \
            (1..%d) drawn at its top-left corner inside a red badge.

            Return ONLY a JSON object on one line, no markdown:
            { "n": <number or -1> }

            where <number> is the label of the element that best matches: %s
            Use -1 if none of the labeled elements match.
            """;

    /**
     * Run the Set-of-Mark grounding attempt against an already-fetched
     * screenshot. Returns a {@link GroundingResult.Hit} on a confident pick, or
     * a {@link GroundingResult.Miss} (with a short reason) on every non-hit
     * outcome so the caller can fall through to the coord path. Never throws.
     */
    private GroundingResult attemptSetOfMark(ModelConfigEntity visionModel,
                                             PageScreenshot shot,
                                             PageSnapshot snapshot,
                                             String intent) {
        List<PageSnapshot.Line> candidates = selectCandidates(snapshot);
        if (candidates.isEmpty()) {
            return new GroundingResult.Miss("vision: set-of-mark had no candidate boxes");
        }

        List<BBox> boxes = candidates.stream().map(PageSnapshot.Line::bbox).toList();
        SetOfMarkAnnotator.Annotated annotated =
                setOfMarkAnnotator.annotate(shot.pngBytes(), boxes, snapshot.viewport());

        String content;
        try {
            ChatModel chatModel = chatModelFactory.buildFor(visionModel, retryTemplate);
            ChatClient client = ChatClient.create(chatModel);
            UserMessage userMessage = UserMessage.builder()
                    .text(String.format(SET_OF_MARK_PROMPT_TEMPLATE, candidates.size(), intent))
                    .media(List.of(new Media(
                            MimeType.valueOf(MimeTypeUtils.IMAGE_JPEG_VALUE),
                            new ByteArrayResource(annotated.jpegBytes()))))
                    .build();
            content = client.prompt()
                    .messages(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.debug("[vision] set-of-mark LLM call failed: {}", e.getMessage());
            return new GroundingResult.Miss("vision: set-of-mark LLM call failed: " + e.getMessage());
        }

        int n = parseSelectedNumber(content);
        if (n < 1 || n > candidates.size()) {
            return new GroundingResult.Miss("vision: set-of-mark returned no match (n=" + n + ")");
        }
        PageSnapshot.Line chosen = candidates.get(n - 1);
        return new GroundingResult.Hit(
                new GroundedTarget(chosen.bbox(), chosen.refId()),
                "set-of-mark #" + n);
    }

    /**
     * Build the SoM candidate list from the current snapshot, in document
     * order, capped at {@link #MAX_SOM_CANDIDATES}.
     *
     * <p>Selection rule (the documented assumption):
     * <ol>
     *   <li>Prefer lines that have a bbox AND a plausibly-clickable role
     *       ({@link #CLICKABLE_ROLES}); a {@code generic} role only qualifies
     *       when it carries a non-blank accessible name.</li>
     *   <li>If that yields nothing, fall back to ALL lines that have a bbox.</li>
     * </ol>
     */
    private List<PageSnapshot.Line> selectCandidates(PageSnapshot snapshot) {
        List<PageSnapshot.Line> withBox = snapshot.lines().stream()
                .filter(line -> line.bbox() != null)
                .toList();

        List<PageSnapshot.Line> clickable = withBox.stream()
                .filter(this::isClickableCandidate)
                .limit(MAX_SOM_CANDIDATES)
                .collect(Collectors.toList());

        if (!clickable.isEmpty()) {
            return clickable;
        }
        return withBox.stream()
                .limit(MAX_SOM_CANDIDATES)
                .collect(Collectors.toList());
    }

    private boolean isClickableCandidate(PageSnapshot.Line line) {
        String role = line.role() == null ? "" : line.role().toLowerCase(Locale.ROOT);
        if (!CLICKABLE_ROLES.contains(role)) {
            return false;
        }
        // A bare generic node is too noisy to mark unless it is actually labeled.
        if ("generic".equals(role)) {
            return line.name() != null && !line.name().isBlank();
        }
        return true;
    }

    /**
     * Parse the SoM reply for {@code {"n": <int>}}. Reuses {@link #extractJsonBlock}
     * so code-fence / commentary wrappers are tolerated. Returns {@code -1} on
     * any parse failure or a missing field so the caller falls through.
     */
    private int parseSelectedNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            JsonNode node = mapper.readTree(extractJsonBlock(raw));
            JsonNode n = node.path("n");
            if (n.isMissingNode() || n.isNull() || !n.isNumber()) {
                return -1;
            }
            return n.asInt(-1);
        } catch (Exception e) {
            log.debug("[vision] set-of-mark parse failed: {}", e.getMessage());
            return -1;
        }
    }

    /** Build a human-readable intent string from the hint. */
    private String describeIntent(GroundingHint hint) {
        return switch (hint) {
            case GroundingHint.A11yMatch m -> "the " + m.role()
                    + " whose accessible name matches /" + m.namePattern().pattern() + "/";
            case GroundingHint.ByRefId r ->
                // unreachable — guarded in ground(), but kept for exhaustiveness.
                    "element with refId " + r.refId();
        };
    }

    /**
     * Parse the LLM reply into a {@link GroundingResult}. Tolerant of:
     * <ul>
     *   <li>Code-fence wrappers (```json ... ```)</li>
     *   <li>Surrounding whitespace / commentary lines before the JSON block</li>
     * </ul>
     * Returns Miss on any parse failure rather than throwing.
     */
    private GroundingResult parseAndThreshold(String raw) {
        if (raw == null || raw.isBlank()) {
            return new GroundingResult.Miss("vision: LLM returned empty response");
        }
        String body = extractJsonBlock(raw);
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (Exception e) {
            return new GroundingResult.Miss("vision: could not parse LLM JSON: " + e.getMessage());
        }
        boolean found = node.path("found").asBoolean(false);
        double confidence = node.path("confidence").asDouble(0.0);
        String reason = node.path("reason").asText("");

        if (!found) {
            return new GroundingResult.Miss("vision: " + (reason.isBlank() ? "not found" : reason));
        }
        // P0-3-A2: do not promote below the threshold even if found=true.
        if (confidence < CONFIDENCE_THRESHOLD) {
            return new GroundingResult.Miss(
                    "vision: low confidence: " + confidence
                            + (reason.isBlank() ? "" : " (" + reason + ")"));
        }

        int x = node.path("x").asInt(0);
        int y = node.path("y").asInt(0);
        int w = node.path("w").asInt(0);
        int h = node.path("h").asInt(0);
        if (w < 0 || h < 0) {
            return new GroundingResult.Miss("vision: invalid bbox dimensions w=" + w + " h=" + h);
        }
        return new GroundingResult.Hit(
                new GroundedTarget(new BBox(x, y, w, h), null),
                "vision (confidence=" + confidence
                        + (reason.isBlank() ? ")" : ", " + reason + ")"));
    }

    /** Permissively yank a JSON object out of an LLM reply. */
    private static String extractJsonBlock(String raw) {
        String trimmed = raw.trim();
        // Strip optional ```json fences.
        Matcher fence = CODE_FENCE.matcher(trimmed);
        if (fence.find()) {
            return fence.group(1).trim();
        }
        // Otherwise grab from the first '{' to the matching last '}' on the line.
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private static final Pattern CODE_FENCE = Pattern.compile(
            "```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```",
            Pattern.CASE_INSENSITIVE);
}
