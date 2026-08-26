# CodeCarry 1.10.0

Make native Chat a scannable developer timeline, and refresh session status when the app returns from the background.

## Changes

- Think, Skill, and other tool calls are independent timeline rows. Loading a skill shows `Skill {name}` instead of hiding it inside a bubble fold.
- Assistant prose is left-aligned with no Response chrome. User messages stay in bubbles. Copy remains on long-press; the last assistant prose row shows a quiet `time · model` line.
- Process rows start collapsed. The existing auto-expand setting now means process-row details start expanded.
- Returning from background refreshes OpenCode busy/unread and session lists, and Ready DSH workspace/session catalogs, without opening Chat. An already-open conversation merges only that session's history. A failed Ready DSH catalog refetch reconnects mux+host.

## Verification

- Passed `:app:testDebugUnitTest` (585 tests).
- Passed `:app:assembleDebug`.
- Emulator, device, and real-session E2E were not run for this release.

## Metadata

- `versionName`: `1.10.0`
- `versionCode`: `109`
