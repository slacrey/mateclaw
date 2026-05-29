package vip.mate.browser.pairing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.tool.builtin.ExtensionBrowserTool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Phase 3.1 — Browser extension pairing (contract §3).
 *
 * <p>The admin UI calls these endpoints with its normal JWT session to mint a
 * browser-scoped Personal Access Token for the logged-in user, then pushes the
 * plaintext to the Chrome extension (which authenticates the direct-WSS
 * handshake with it via the {@code Sec-WebSocket-Protocol bearer.<token>}
 * entry — see {@code EdgeAuthInterceptor}).
 *
 * <p>Thin wrapper over {@link PersonalAccessTokenService}: minting reuses the
 * normal PAT create path (plaintext returned exactly once), revoke reuses the
 * owner-scoped soft-delete. The current user is resolved from the security
 * context the same way {@code PersonalAccessTokenController} does — never from a
 * client-supplied id — so one user can never mint or revoke for another.
 */
@Tag(name = "Browser Pairing")
@RestController
@RequestMapping("/api/v1/browser/pairing")
@RequiredArgsConstructor
public class BrowserPairingController {

    /** PAT name prefix; full name is {@code browser-extension/<deviceName>}. */
    static final String TOKEN_NAME_PREFIX = "browser-extension";

    /** Default device label when the caller omits one. */
    static final String DEFAULT_DEVICE_NAME = "browser-extension";

    /** Scope stamped on minted tokens (forward-compat with the EdgeAuthInterceptor TODO). */
    static final String EDGE_SCOPE = "browser:edge";

    /** Reasonable default lifetime for an extension pairing token. */
    static final long TTL_DAYS = 90;

    private final PersonalAccessTokenService patService;
    private final AuthService authService;
    private final ExtensionBrowserTool browserTool;

    @Operation(summary = "Mint a browser-scoped PAT for the extension (plaintext returned once)")
    @PostMapping("/mint-token")
    public R<MintResponse> mintToken(@RequestBody(required = false) MintRequest req, Authentication auth) {
        UserEntity user = requireUser(auth);

        String deviceName = (req == null || req.deviceName() == null || req.deviceName().isBlank())
                ? DEFAULT_DEVICE_NAME
                : req.deviceName().trim();
        String name = TOKEN_NAME_PREFIX + "/" + deviceName;
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(TTL_DAYS);

        PersonalAccessTokenService.CreatedToken created =
                patService.create(user.getId(), name, EDGE_SCOPE, expiresAt);

        return R.ok(new MintResponse(
                created.plaintext(),
                String.valueOf(created.id()),
                expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
    }

    @Operation(summary = "Revoke a previously minted pairing token (owner only)")
    @PostMapping("/revoke-token")
    public R<Map<String, Object>> revokeToken(@RequestBody RevokeRequest req, Authentication auth) {
        UserEntity user = requireUser(auth);
        if (req == null || req.tokenId() == null || req.tokenId().isBlank()) {
            throw new MateClawException("err.auth.pat_not_found", "tokenId is required");
        }
        Long tokenId = parseTokenId(req.tokenId());
        // Service enforces owner-scoping: throws err.auth.pat_not_found when the
        // token is missing OR belongs to another user, so a caller can never
        // revoke someone else's token.
        patService.revoke(tokenId, user.getId());
        return R.ok(Map.of("ok", true));
    }

    /**
     * Dev/diagnostic: drive the connected extension through the REAL
     * {@link ExtensionBrowserTool} path (navigate → observe) and return both
     * raw JSON results. Lets an automated harness verify the full
     * server↔extension↔CDP round-trip without going through the LLM. Admin-JWT
     * gated like the rest of this controller.
     */
    @Operation(summary = "Dev: drive the connected extension (navigate + observe) and return raw results")
    @PostMapping("/test-drive")
    public R<Map<String, Object>> testDrive(@RequestBody(required = false) TestDriveRequest req,
                                            Authentication auth) {
        requireUser(auth);
        String url = (req == null || req.url() == null || req.url().isBlank())
                ? "https://example.com" : req.url().trim();
        String navigate = browserTool.extension_browser_navigate(url, "load", null);
        // "all" so the probe captures headings/text (e.g. the page title), not
        // just interactive elements — confirms the agent can actually read content.
        String observe = browserTool.extension_browser_observe("all", null);
        return R.ok(Map.of("navigate", navigate, "observe", observe));
    }

    private Long parseTokenId(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new MateClawException("err.auth.pat_not_found", "PAT not found: " + raw);
        }
    }

    private UserEntity requireUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new MateClawException("err.auth.unauthenticated", "Authentication required");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found",
                    "Authenticated user not found: " + auth.getName());
        }
        return user;
    }

    /** Inbound DTO for {@link #mintToken}. {@code deviceName} is optional. */
    public record MintRequest(String deviceName) {}

    /** Inbound DTO for {@link #revokeToken}. */
    public record RevokeRequest(String tokenId) {}

    /** Inbound DTO for {@link #testDrive}. {@code url} defaults to example.com. */
    public record TestDriveRequest(String url) {}

    /**
     * Mint response (contract §3). {@code token} is the one-shot plaintext;
     * {@code tokenId} is a string for later revoke; {@code expiresAt} is ISO-8601.
     */
    public record MintResponse(String token, String tokenId, String expiresAt) {}
}
