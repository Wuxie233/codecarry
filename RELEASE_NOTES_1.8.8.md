# OC Remote 1.8.8

Refine the native Chat and Sessions layout for phones, tablets, and expanded windows.

## Changes

- Add a compact Chat header that keeps session context visible and moves secondary actions into the existing overflow menu on narrow windows.
- Use `WindowSizeClass` for phone, tablet, and expanded Chat layouts, with replacement context surfaces on smaller windows and a persistent 360dp context pane on expanded windows.
- Consolidate retry, Roundtable, permission, and question responses above the primary composer while preserving request ownership and ordered decisions.
- Preserve a manually scrolled-up conversation when streaming content arrives and show a new-messages control that returns to the live tail.
- Improve selected-server Sessions scanability with one recent-work, view, search, and project hierarchy capped at a readable width on large screens.
- Keep OpenCode, Pi Stack, Pi Roundtable, Codex, Markdown, terminal, draft, attachment, archive, and server-scoping behavior unchanged.

## Verification

- Passed the complete `:app:testDebugUnitTest` suite.
- Passed `:app:compileDebugAndroidTestKotlin`.
- Passed `:app:assembleDebug` and `:app:assembleRelease`, including release R8 and lint vital checks.
- Emulator, device, and real-session E2E were not run for this release.
