# CodeCarry 1.10.1

Stop drawing the same Shell or MCP call twice.

## Changes

- A DSH `assistant/message` already embeds `tool-call` blocks. A later mux `tool/call` with the same `callId` no longer appends a second row.

## Verification

- Passed `:app:testDebugUnitTest` (588 tests).
- Passed `:app:assembleDebug`.
- Emulator, device, and real-session E2E were not run for this release.

## Metadata

- `versionName`: `1.10.1`
- `versionCode`: `110`
