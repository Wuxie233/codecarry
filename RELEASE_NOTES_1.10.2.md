# CodeCarry 1.10.2

Pick a host directory (or No Repo) and start a DSH conversation from Sessions.

## Changes

- DSH Sessions shows + again. Tapping it opens the in-app host directory browser.
- Choosing a directory registers that workspace, reuses an unarchived blank session when one already belongs there, otherwise creates with `workspaceId` and opens Chat.
- A No Repo row starts a conversation without a project folder.
- Existing group "new here" and Chat "new session" use the same attach path, so a bare `cwd` no longer lands Ungrouped.
- Create-folder stays in the picker. Directory search stays OpenCode-only. Rehome still requires a real directory.

## Verification

- Passed `:app:testDebugUnitTest` (597 tests).
- Passed `:app:assembleDebug`.
- Emulator, device, and real-session E2E were not run for this release.

## Metadata

- `versionName`: `1.10.2`
- `versionCode`: `111`
