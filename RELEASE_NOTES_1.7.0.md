# OC Remote v1.7.0 - Release Notes

## Highlights

- Improves chat text selection reliability by reducing gesture conflicts with swipe-to-revert and horizontally scrollable content.
- Restores horizontal dragging for markdown tables and code blocks inside chat messages.
- Hardens new-session and fork navigation so project directory context is preserved across chat routes.
- Fixes historical/subagent session loading paths, including notification deep links, share targets, and active conversation cards, by carrying the session directory into `x-opencode-directory` requests.
- Improves dark-theme readability for diff additions/deletions and syntax-highlighted code.
- Prevents crashes from compact or non-object OpenCode error payloads in message and retry rendering.

## Tests

- `:app:compileDebugKotlin` - run for this release.
- `:app:testDebugUnitTest` - run for this release.

## Version

- `versionName`: `1.7.0`
- `versionCode`: `44`

## Known limitations

- Manual device gesture smoke testing was not available in this environment; release validation relies on code review, compilation, and unit tests.

## Artifact

- Artifact: `oc-remote-1.7.0.apk`
- SHA-256: GitHub Actions release workflow uploads the signed APK artifact.
- Signature verification: GitHub Actions release workflow verifies the signed APK with `apksigner`.
