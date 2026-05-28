package vip.mate.browser.edge.auth;

/**
 * Authenticated edge principal.
 *
 * <p>{@code subject} carries either the JWT subject (username) or the PAT
 * owner's {@code userId.toString()} — see {@link EdgeAuthInterceptor} for
 * how each is produced. Long-typed identity is introduced in Phase 4.
 *
 * <p>{@code scheme} is "jwt" or "pat" for observability/audit.
 */
public record EdgePrincipal(String subject, String scheme) {}
