# CodeCarry 1.8.9

Improve native OpenCode chat readiness and history recovery.

## Changes

- Accept native OpenCode messages before the session history finishes loading and drain them in FIFO order once the route is available.
- Keep queued sends appendable while a request is in flight, retain a failed queue head for explicit retry, and preserve each request's model and agent selection.
- Show available live messages while REST history is loading instead of waiting for the full snapshot.
- Merge late limit-based REST history with live reducer state so newer streamed messages and parts are not rolled back.
- Update the CodeCarry package identity, branding links, and cursor route icon.

## Verification

- Passed the complete `:app:testDebugUnitTest` suite (709 tests).
- Passed `:app:compileDebugAndroidTestKotlin`.
- Passed `:app:assembleDebug` and `:app:assembleRelease`.
- Emulator, device, and real-session E2E were not run for this release.
