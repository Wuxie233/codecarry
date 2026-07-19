# OC Remote 1.7.49

This release keeps OpenCode work and status information accurate within each server control surface, including after the app returns from the background.

## OpenCode Workspace

- Move Recent Work from the global Home screen into the selected OpenCode server's Sessions control surface.
- Keep recent sessions isolated to the current server and preserve their effective running or retry status.
- Complete Simplified Chinese translations for the workspace, activity queue, filters, and subagent drawer.

## Background Reliability

- Detect silent half-open SSE connections and enter the existing reconnect loop instead of waiting indefinitely.
- Reconcile connected OpenCode session statuses when the app returns to the foreground without adding background polling.
- Preserve newer live events during REST reconciliation and avoid restoring deleted or cross-server sessions.
- Isolate response and permission notification deduplication by server.
- Keep the Retry Now action latched until the authoritative session status exits retry.

## Verification

- Passed the full debug unit test suite.
- Built the debug and Android test APKs successfully.
- Verified Simplified Chinese Home rendering and background-to-foreground recovery on an Android emulator.
