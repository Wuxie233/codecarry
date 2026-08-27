# CodeCarry 1.10.3

Restore streaming chat smoothness by stopping full-document work on every token.

## Changes

- Streaming Markdown now extends a selectable tail in place, or reparses only the open suffix. Completed tables, fences, and earlier blocks keep their identities.
- Chat no longer replans unchanged earlier messages while the live part grows.
- DSH live history folds incrementally: later mux chunks reuse earlier folded messages instead of rebuilding the whole session.
- Unchanged OpenCode/DSH reducer snapshots no longer emit a new `ChatUiState` messages list, so the timeline does not recompose from the top on every token.

## Verification

- Passed focused JVM tests: `ChatStreamingHotPathTest`, `MarkdownStreamingPlanTest`, `ChatMessageRowPlannerTest`, `DshChatFoldTest` (37 tests).
- Passed `:app:testDebugUnitTest` (602 tests).
- Passed `:app:assembleDebug`.
- Emulator, device, and real-session E2E were not run for this release.

## Metadata

- `versionName`: `1.10.3`
- `versionCode`: `112`
