# OC Remote v1.7.6 - Release Notes

## Highlights

- Added a message action sheet from chat bubbles with **Fork from here**, **Copy text**, **Copy as Markdown**, **Quote into input**, and **Restore to this message**.
- Forking from a message now creates a new session at that message and navigates into it immediately.
- Restore-to-message now requires confirmation and stays disabled while the session is busy or streaming.
- Improved chat usability with pending-action cues, clearer disabled-send reasons, loading labels, and horizontally scrollable markdown tables.
- Added server password show/hide controls and localized retry/loading affordances.

## Tests

- Message action helper and fork payload coverage added.
- Markdown table parsing coverage added.
- Release verification target: `:app:testDebugUnitTest` and `:app:assembleDebug`.

## Version

- `versionName`: `1.7.6`
- `versionCode`: `50`

## Known limitations

- Device-level manual QA was intentionally skipped for this release per maintainer instruction; validation relies on code review, compilation, unit tests, debug APK build, and GitHub Actions release signing checks.

## Artifact

- Artifact: pending release workflow upload as `oc-remote-1.7.6.apk`.
- SHA-256: pending GitHub Actions release workflow output.
- Signature verification: pending GitHub Actions release workflow verification with `apksigner`.
