# MateClaw Browser Bridge (Native Host)

Per-user-machine daemon that bridges the MateClaw Chrome Extension
(over Chrome Native Messaging / stdio) to the MateClaw Control Plane
(over Bearer-authenticated WSS).

Phase 1 scope: hello / heartbeat / ping pipeline.

## Build

    pnpm build        # compile TypeScript → dist/
    pnpm test         # run unit tests
    pnpm dev          # tsx watch (for development)

## Run

    node dist/cmd/bridge.js --version
    node dist/cmd/bridge.js run
