# OC Remote v1.6.30 — Release Notes

## Highlights

- Fixed a crash that could happen when creating a new conversation against OpenCode servers that return a minimal session response.
- The client now tolerates missing `time` metadata in session payloads and defaults missing timestamps to `0` instead of failing deserialization.

## Tests

- `:app:testDebugUnitTest --tests "dev.minios.ocremote.data.api.OpenCodeApiForkTest"`
- `:app:assembleRelease`

## Version

- `versionName`: `1.6.30`
- `versionCode`: `43`

## Known limitations

- Manual device smoke testing was not run in this environment.

## Artifact

- Artifact: `oc-remote-1.6.30.apk`
- SHA-256: GitHub Actions release workflow uploads the signed APK artifact.
- Signature verification: GitHub Actions release workflow verifies the signed APK with `apksigner`.
