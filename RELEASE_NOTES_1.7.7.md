# OC Remote v1.7.7 - Release Notes

## Highlights

- Added **Pi Roundtable** as a dedicated server type with a Roundtable Center instead of mapping Pi rooms onto OpenCode sessions.
- Added persona library management for Pi servers, including create/edit/clone/delete flows, MBTI/action-tag metadata, JSON import/export, and AI-assisted persona draft generation.
- Added per-role gateway/model catalog configuration, validation, lineup proposal review, reusable lineup templates, and configurable speaker cadence.
- Added multi-speaker Roundtable chat bubbles with stable persona accents, moderator synthesis styling, retry/fallback/error indicators, and steering controls for continue, stop, deep-dive, mentions, inject, cadence switch, new persona, and skip.
- Added offline Mermaid rendering for Roundtable synthesis, summary/export surfaces, large-transcript rendering safeguards, reconnect hardening, background completion notifications, and token-safe Pi navigation.

## Tests

- Release verification target: `:app:testDebugUnitTest` and `:app:assembleDebug`.
- GitHub Actions release workflow verifies tag/version alignment, signed release APK creation, APK metadata, and APK signature.

## Version

- `versionName`: `1.7.7`
- `versionCode`: `51`

## Known limitations

- Device-level manual QA is not included in this release task; validation relies on code review, Gradle unit tests, debug APK assembly, and GitHub Actions release signing checks.

## Artifact

- Artifact: pending release workflow upload as `oc-remote-1.7.7.apk`.
- SHA-256: pending GitHub Actions release workflow output.
- Signature verification: pending GitHub Actions release workflow verification with `apksigner`.
