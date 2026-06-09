# mateclaw-extension

Chrome MV3 extension — MateClaw Browser Agent (Phase 1 scaffold).

## Development

```bash
pnpm install
pnpm test       # Vitest unit tests
pnpm build      # Produces dist/
```

## Permissions (Phase 1)

`sidePanel`, `storage`, `alarms`, `notifications`, `nativeMessaging`.

`debugger` and `offscreen` are deferred to Phase 2.
