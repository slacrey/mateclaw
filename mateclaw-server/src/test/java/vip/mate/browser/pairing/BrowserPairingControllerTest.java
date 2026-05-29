package vip.mate.browser.pairing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.pat.PersonalAccessTokenEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 3.1 W3.1 — covers {@link BrowserPairingController}:
 * <ul>
 *   <li>mint returns plaintext + tokenId + future ISO-8601 expiry and creates a
 *       PAT named {@code browser-extension/<deviceName>}, scope {@code browser:edge},
 *       ~90-day TTL, for the caller resolved from the security context;</li>
 *   <li>missing/blank deviceName falls back to the default;</li>
 *   <li>revoke delegates to the owner-scoped service revoke and returns ok;</li>
 *   <li>only the owner can revoke — the controller passes the caller's id (never a
 *       client-supplied user id), and the service's not-found guard propagates.</li>
 * </ul>
 */
class BrowserPairingControllerTest {

    private static final long CALLER_ID = 4242L;

    private PersonalAccessTokenService patService;
    private AuthService authService;
    private BrowserPairingController controller;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        patService = mock(PersonalAccessTokenService.class);
        authService = mock(AuthService.class);
        controller = new BrowserPairingController(patService, authService);

        auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice");
        UserEntity user = new UserEntity();
        user.setId(CALLER_ID);
        user.setUsername("alice");
        when(authService.findByUsername("alice")).thenReturn(user);
    }

    @Test
    void mintToken_returnsPlaintextTokenIdAndFutureExpiry_andCreatesScopedPatForCaller() {
        var entity = new PersonalAccessTokenEntity();
        entity.setId(900100L);
        when(patService.create(eq(CALLER_ID), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(new PersonalAccessTokenService.CreatedToken(900100L, "mc_secretplain", entity));

        LocalDateTime before = LocalDateTime.now().plusDays(BrowserPairingController.TTL_DAYS).minusMinutes(1);
        R<BrowserPairingController.MintResponse> r =
                controller.mintToken(new BrowserPairingController.MintRequest("Work Laptop"), auth);
        LocalDateTime after = LocalDateTime.now().plusDays(BrowserPairingController.TTL_DAYS).plusMinutes(1);

        BrowserPairingController.MintResponse body = r.getData();
        assertThat(body.token()).isEqualTo("mc_secretplain");
        assertThat(body.tokenId()).isEqualTo("900100");
        // ISO-8601 local date-time, parseable, and ~90 days out.
        LocalDateTime parsed = LocalDateTime.parse(body.expiresAt());
        assertThat(parsed).isAfter(before).isBefore(after);

        // Verify the PAT was minted for the caller with the contracted name/scope/TTL.
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> exp = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(patService).create(eq(CALLER_ID), name.capture(), scope.capture(), exp.capture());
        assertThat(name.getValue()).isEqualTo("browser-extension/Work Laptop");
        assertThat(scope.getValue()).isEqualTo("browser:edge");
        assertThat(exp.getValue()).isEqualTo(parsed); // response echoes the persisted expiry
    }

    @Test
    void mintToken_defaultsDeviceName_whenBodyNullOrBlank() {
        var entity = new PersonalAccessTokenEntity();
        entity.setId(1L);
        when(patService.create(eq(CALLER_ID), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(new PersonalAccessTokenService.CreatedToken(1L, "mc_x", entity));

        controller.mintToken(null, auth);
        controller.mintToken(new BrowserPairingController.MintRequest("   "), auth);

        verify(patService, times(2)).create(eq(CALLER_ID),
                eq("browser-extension/browser-extension"), eq("browser:edge"), any());
    }

    @Test
    void revokeToken_delegatesToOwnerScopedRevoke_andReturnsOk() {
        R<Map<String, Object>> r =
                controller.revokeToken(new BrowserPairingController.RevokeRequest("900100"), auth);

        assertThat(r.getData()).containsEntry("ok", true);
        // Owner-scoping: revoke is always called with the CALLER's id, never a
        // client-supplied user id.
        verify(patService).revoke(900100L, CALLER_ID);
    }

    @Test
    void revokeToken_propagatesNotFound_whenServiceRejectsForeignToken() {
        // Service throws when the token belongs to another user — a caller can
        // never revoke someone else's token.
        doThrow(new MateClawException("err.auth.pat_not_found", "PAT not found or not owned by current user"))
                .when(patService).revoke(eq(5L), eq(CALLER_ID));

        assertThatThrownBy(() ->
                controller.revokeToken(new BrowserPairingController.RevokeRequest("5"), auth))
                .isInstanceOf(MateClawException.class);
    }

    @Test
    void revokeToken_rejectsMissingTokenId() {
        assertThatThrownBy(() ->
                controller.revokeToken(new BrowserPairingController.RevokeRequest(null), auth))
                .isInstanceOf(MateClawException.class);
        assertThatThrownBy(() ->
                controller.revokeToken(new BrowserPairingController.RevokeRequest("not-a-number"), auth))
                .isInstanceOf(MateClawException.class);
        verify(patService, never()).revoke(any(), any());
    }

    @Test
    void mintToken_rejectsUnauthenticated() {
        Authentication anon = mock(Authentication.class);
        when(anon.getName()).thenReturn(null);
        assertThatThrownBy(() ->
                controller.mintToken(new BrowserPairingController.MintRequest("x"), anon))
                .isInstanceOf(MateClawException.class);
        verifyNoInteractions(patService);
    }
}
