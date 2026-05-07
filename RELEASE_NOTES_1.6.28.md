# OC Remote v1.6.28 — Release Notes

## Highlights

- Chat retry status now stays near the current interaction area above the composer, so active retry/error information is visible without scrolling back to the top of the message list.
- Active conversation ordering now prioritizes unread conversations before busy/running or retrying conversations, making new user-visible updates easier to find.
- This release is based on the v1.6.27 MCP runtime toggle parity line and includes the scoped chat visibility/order fixes from issue #22.

## Tests

- `:app:testDebugUnitTest` ✅
- `:app:assembleDebug` ✅

## Version

- `versionName`: `1.6.28`
- `versionCode`: `41`

## Known limitations

- Historical retry parts remain in their original message timeline positions. Only the live/current retry status is moved near the bottom interaction area.
- Manual device smoke testing was not run in this environment.

## Artifact

- Artifact: `oc-remote-1.6.28.apk`
- SHA-256: <fill in after build>
- Signature verification: GitHub Actions release workflow verifies the signed APK with `apksigner`.
